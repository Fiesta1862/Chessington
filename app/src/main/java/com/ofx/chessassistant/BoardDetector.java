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

    private int stableHits;
    private int prevLeft = -1, prevTop = -1, prevSize = -1;

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
        char[] map = new char[64];
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++)
                map[r * 8 + f] = classifySquare(bmp, left + f * sq, top + r * sq, sq);

        if (!lastMapValid) {
            // calibration frame: detect orientation + engine color from piece placement.
            // engine plays the color whose pieces sit in the bottom half of the screen.
            int whiteTop = 0, whiteBottomRows = 0;
            for (int f = 0; f < 8; f++) {
                if (map[0 * 8 + f] == 'W' || map[1 * 8 + f] == 'W') whiteTop++;
                if (map[6 * 8 + f] == 'W' || map[7 * 8 + f] == 'W') whiteBottomRows++;
            }
            whiteBottom = whiteBottomRows >= whiteTop;
            engineColor = whiteBottom ? 'w' : 'b';
            sideToMove = (char) ('w' + 'b' - engineColor); // assume opponent moves first

            lastMap = map;
            lastMapValid = true;
            android.util.Log.i("Chessington", "calibrated: whiteBottom=" + whiteBottom
                    + " engine=" + engineColor);
            return new Frame(false, null, left, top, size, whiteBottom, sideToMove, engineColor);
        }

        if (!Arrays.equals(map, lastMap)) {
            // a ply was played; side to move flips
            sideToMove = (char) ('w' + 'b' - sideToMove);
            android.util.Log.i("Chessington", "ply detected: side=" + sideToMove);
            lastMap = map;
        }

        refreshTypes(bmp, left, top, size, sq);
        String fen = buildFen(map);
        if (fen == null) return new Frame(false, null, left, top, size, whiteBottom, sideToMove, engineColor);
        android.util.Log.i("Chessington", "fen=" + fen);
        return new Frame(true, fen, left, top, size, whiteBottom, sideToMove, engineColor);
    }

    // last seen piece map, 'W'/'B'/0, in ROW-MAJOR screen order (row 0 = top)
    private char[] lastMap = new char[64];

    /**
     * Absolute occupancy detection — no reference frame needed, works mid-game.
     * Empty squares are uniform marble; pieces (chess.com style: white fill with
     * dark outline, or near-black fill) create strong internal contrast.
     * Color from the center region mean: white fill ~250, black fill ~30.
     */
    private char classifySquare(Bitmap bmp, int x, int y, int sq) {
        int x0 = x + (int) (sq * 0.30f), y0 = y + (int) (sq * 0.30f);
        int x1 = x + (int) (sq * 0.70f), y1 = y + (int) (sq * 0.70f);

        int min = 255, max = 0;
        long centerSum = 0;
        int n = 0;
        for (int yy = y0; yy < y1 && yy < bmp.getHeight(); yy += 2) {
            for (int xx = x0; xx < x1 && xx < bmp.getWidth(); xx += 2) {
                int px = bmp.getPixel(xx, yy);
                int luma = ((px >> 16 & 0xFF) * 299 + (px >> 8 & 0xFF) * 587 + (px & 0xFF) * 114) / 1000;
                if (luma < min) min = luma;
                if (luma > max) max = luma;
                centerSum += luma;
                n++;
            }
        }
        if (n == 0) return 0;
        float mean = centerSum / (float) n;

        // white piece fill ~250, black piece fill ~20, square marble ~145 to ~235
        if (mean >= 238f) return 'W';
        if (mean <= 70f) return 'B';

        // some themes render hollow rings or outlined pieces: internal contrast
        // rescues those. light theme square luma sits well below white fill.
        if ((max - min) > 45f) {
            if (mean > 165f) return 'W';
            if (mean < 110f) return 'B';
        }
        return 0;
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
