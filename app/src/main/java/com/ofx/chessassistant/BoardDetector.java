package com.ofx.chessassistant;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.Image;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Finds the 8x8 board in a screenshot and classifies each square.
 * Detects board orientation (white at bottom or top — chess.com flips the board
 * when you play black) and tracks side-to-move from occupancy changes.
 */
public class BoardDetector {

    public static class Frame {
        public final boolean stable;
        public final String fen;
        public final int left, top, size;
        public final boolean whiteBottom;
        public final char sideToMove; // 'w' or 'b'
        public final char engineColor; // color the assistant plays

        Frame(boolean stable, String fen, int left, int top, int size,
              boolean whiteBottom, char sideToMove, char engineColor) {
            this.stable = stable;
            this.fen = fen;
            this.left = left;
            this.top = top;
            this.size = size;
            this.whiteBottom = whiteBottom;
            this.sideToMove = sideToMove;
            this.engineColor = engineColor;
        }

        /** square "e4" -> screen px rect, honoring detected orientation */
        public RectF squareRect(String square) {
            int file = square.charAt(0) - 'a';
            int rank = square.charAt(1) - '0';
            int row, col;
            if (whiteBottom) {
                row = 8 - rank;
                col = file;
            } else {
                row = rank - 1;
                col = 7 - file;
            }
            float sq = size / 8f;
            float x = left + col * sq;
            float y = top + row * sq;
            return new RectF(x, y, x + sq, y + sq);
        }
    }

    private static final int SEARCH_STABLE_COUNT = 2;
    private static final float OCCUPANCY_DELTA = 12f;

    private int stableHits;
    private int prevLeft = -1, prevTop = -1, prevSize = -1;

    // per-square empty reference luminance from the calibration frame
    private final float[] emptyRef = new float[64];
    private boolean refValid;

    // last seen piece map, 'W'/'B'/0, in ROW-MAJOR screen order (row 0 = top)
    private final char[] lastMap = new char[64];
    private boolean lastMapValid;

    private boolean whiteBottom = true;
    private char engineColor = 'w';
    private char sideToMove = 'b';

    public static Bitmap imageToBitmap(Image image, int rowStride, int pixelStride) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            buf.position(y * rowStride);
            for (int x = 0; x < width; x++) {
                int px = buf.getInt();
                int r = (px) & 0xFF, g = (px >> 8) & 0xFF, b = (px >> 16) & 0xFF;
                pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    public Frame analyze(Bitmap bmp) {
        int[] bounds = findBoard(bmp);
        if (bounds == null) return new Frame(false, null, 0, 0, 0, whiteBottom, sideToMove, engineColor);
        int left = bounds[0], top = bounds[1], size = bounds[2];

        boolean positionStable = left == prevLeft && top == prevTop && size == prevSize;
        prevLeft = left; prevTop = top; prevSize = size;

        if (!positionStable) {
            stableHits = 0;
            return new Frame(false, null, left, top, size, whiteBottom, sideToMove, engineColor);
        }
        stableHits++;
        if (stableHits < SEARCH_STABLE_COUNT)
            return new Frame(false, null, left, top, size, whiteBottom, sideToMove, engineColor);

        int sq = size / 8;
        float[] lum = new float[64];
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++)
                lum[r * 8 + f] = squareLuma(bmp, left + f * sq, top + r * sq, sq);

        if (!refValid) {
            // calibration frame: reference luminance + orientation + engine color.
            // engine plays the color whose pieces sit in the bottom half of the screen.
            System.arraycopy(lum, 0, emptyRef, 0, 64);
            refValid = true;

            char[] map = classify(lum, bmp, left, top, sq);
            int whiteTop = 0, whiteBottomRows = 0;
            for (int f = 0; f < 8; f++) {
                if (map[0 * 8 + f] == 'W' || map[1 * 8 + f] == 'W') whiteTop++;
                if (map[6 * 8 + f] == 'W' || map[7 * 8 + f] == 'W') whiteBottomRows++;
            }
            whiteBottom = whiteBottomRows >= whiteTop;
            engineColor = whiteBottom ? 'w' : 'b';
            sideToMove = (char) ('w' + 'b' - engineColor); // opponent moves first

            System.arraycopy(map, 0, lastMap, 0, 64);
            lastMapValid = true;
            return new Frame(false, null, left, top, size, whiteBottom, sideToMove, engineColor);
        }

        char[] map = classify(lum, bmp, left, top, sq);

        if (lastMapValid && !Arrays.equals(map, lastMap)) {
            // a ply was played; side to move flips
            sideToMove = (char) ('w' + 'b' - sideToMove);
            System.arraycopy(map, 0, lastMap, 0, 64);
        }

        refreshTypes(bmp, left, top, size, sq);
        String fen = buildFen(map);
        if (fen == null) return new Frame(false, null, left, top, size, whiteBottom, sideToMove, engineColor);
        return new Frame(true, fen, left, top, size, whiteBottom, sideToMove, engineColor);
    }

    private char[] classify(float[] lum, Bitmap bmp, int left, int top, int sq) {
        char[] pieces = new char[64];
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                int idx = r * 8 + f;
                float delta = lum[idx] - emptyRef[idx];
                if (Math.abs(delta) < OCCUPANCY_DELTA) continue;
                boolean lightSquare = ((r + f) & 1) == 0;
                // white pieces brighten the square, black pieces darken it.
                // white piece on dark square = big positive; black on light = big negative.
                // white on light / black on dark = small same-sign — parity bias resolves
                // the ambiguous middle: bias light squares by +8, dark squares by -8.
                float biased = delta + (lightSquare ? 8f : -8f);
                pieces[idx] = biased > 0 ? 'W' : 'B';
            }
        }
        return pieces;
    }

    private float squareLuma(Bitmap bmp, int x, int y, int sq) {
        int x0 = x + (int) (sq * 0.25f), y0 = y + (int) (sq * 0.25f);
        int w = (int) (sq * 0.5f), h = (int) (sq * 0.5f);
        long sum = 0;
        int n = 0;
        for (int yy = y0; yy < y0 + h && yy < bmp.getHeight(); yy += 2) {
            for (int xx = x0; xx < x0 + w && xx < bmp.getWidth(); xx += 2) {
                int px = bmp.getPixel(xx, yy);
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                sum += (r * 299 + g * 587 + b * 114) / 1000;
                n++;
            }
        }
        return n == 0 ? 0f : sum / (float) n;
    }

    private int[] findBoard(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int maxBoard = (int) (Math.min(w, h) * 0.98f);
        int minBoard = (int) (Math.min(w, h) * 0.40f);

        int bestLeft = -1, bestTop = -1, bestSize = -1;
        float bestScore = 0;

        for (int size = maxBoard; size >= minBoard; size -= maxBoard / 16) {
            for (int top = 0; top + size <= h; top += size / 8) {
                for (int left = 0; left + size <= w; left += size / 8) {
                    float score = gridScore(bmp, left, top, size);
                    if (score > bestScore) {
                        bestScore = score;
                        bestLeft = left; bestTop = top; bestSize = size;
                    }
                }
            }
        }
        if (bestScore < 18f) return null;
        return new int[]{bestLeft, bestTop, bestSize};
    }

    private float gridScore(Bitmap bmp, int left, int top, int size) {
        int sq = size / 8;
        float contrast = 0;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 7; f++) {
                float la = squareLuma(bmp, left + f * sq, top + r * sq, sq);
                float lb = squareLuma(bmp, left + (f + 1) * sq, top + r * sq, sq);
                contrast += Math.abs(la - lb);
            }
        }
        return contrast / 56f;
    }

    /** FEN rows from the row-major map, honoring orientation. Side and counters tracked live. */
    private String buildFen(char[] map) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 8; rank >= 1; rank--) {
            int row = whiteBottom ? 8 - rank : rank - 1;
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int col = whiteBottom ? file : 7 - file;
                char p = map[row * 8 + col];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                char type = lastType(row, col);
                char out = p == 'W' ? Character.toUpperCase(type) : type;
                sb.append(out);
            }
            if (empty > 0) sb.append(empty);
            if (rank > 1) sb.append('/');
        }
        sb.append(' ').append(sideToMove).append(" - - 0 1");
        return sb.toString();
    }

    // piece-type cache: classified once per square when first seen occupied,
    // kept in lastMap order. Types don't change; occupancy is what moves.
    private final char[][] typeCache = new char[8][8];
    private final boolean[][] typeKnown = new boolean[8][8];
    private int typeLeft = -1, typeTop = -1, typeSize = -1;
    private Bitmap typeBmp;

    private char lastType(int row, int col) {
        if (typeLeft == prevLeft && typeTop == prevTop && typeSize == prevSize && typeKnown[row][col]) {
            return typeCache[row][col];
        }
        return 'P';
    }

    /** classify piece type for every occupied square against the current bitmap */
    public void refreshTypes(Bitmap bmp, int left, int top, int size, int sq) {
        if (typeLeft != left || typeTop != top || typeSize != size) {
            for (char[] row : typeCache) Arrays.fill(row, 'P');
            for (boolean[] row : typeKnown) Arrays.fill(row, false);
        }
        typeLeft = left; typeTop = top; typeSize = size; typeBmp = bmp;
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++)
                if (lastMap[r * 8 + f] != 0 && !typeKnown[r][f]) {
                    typeCache[r][f] = edgeClassify(bmp, left + f * sq, top + r * sq, sq);
                    typeKnown[r][f] = true;
                }
    }

    private char edgeClassify(Bitmap bmp, int x, int y, int sq) {
        int x0 = x + sq / 4, y0 = y + sq / 4, x1 = x + sq * 3 / 4, y1 = y + sq * 3 / 4;
        int transitions = 0;
        int prev = -1;
        for (int yy = y0; yy < y1; yy += 2) {
            for (int xx = x0; xx < x1; xx += 2) {
                int px = bmp.getPixel(xx, yy);
                int luma = ((px >> 16 & 0xFF) * 299 + (px >> 8 & 0xFF) * 587 + (px & 0xFF) * 114) / 1000;
                if (prev >= 0 && Math.abs(luma - prev) > 40) transitions++;
                prev = luma;
            }
        }
        if (transitions < 8) return 'P';
        if (transitions < 14) return 'N';
        if (transitions < 20) return 'B';
        if (transitions < 26) return 'R';
        return 'Q';
    }
}
