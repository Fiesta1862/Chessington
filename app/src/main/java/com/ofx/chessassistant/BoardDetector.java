package com.ofx.chessassistant;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Finds the 8x8 board in a screenshot and classifies each square.
 * Classification: per-square mean luminance compared against the square's own
 * parity baseline (light/dark squares differ by design). Occupied squares shift
 * their mean away from the empty reference sampled on the previous stable frame.
 */
public class BoardDetector {

    public static class Frame {
        public final boolean stable;
        public final String fen;
        public final int left, top, size; // board rect in screen px
        public final boolean whiteToMove;

        Frame(boolean stable, String fen, int left, int top, int size, boolean whiteToMove) {
            this.stable = stable;
            this.fen = fen;
            this.left = left;
            this.top = top;
            this.size = size;
            this.whiteToMove = whiteToMove;
        }

        public RectF squareRect(String square) {
            // square: e.g. "e4", file a..h left->right, rank 1..8 bottom->top (white bottom)
            int file = square.charAt(0) - 'a';
            int rank = 8 - (square.charAt(1) - '0');
            float sq = size / 8f;
            float x = left + file * sq;
            float y = top + rank * sq;
            return new RectF(x, y, x + sq, y + sq);
        }
    }

    private static final int SEARCH_STABLE_COUNT = 2;
    private int stableHits;
    private int prevLeft = -1, prevTop = -1, prevSize = -1;

    // per-square empty reference luminance, refreshed when board found
    private final float[] emptyRef = new float[64];
    private boolean refValid;
    private static final float OCCUPANCY_DELTA = 22f;

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
                int r = (px >> 0) & 0xFF, g = (px >> 8) & 0xFF, b = (px >> 16) & 0xFF;
                pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    public Frame analyze(Bitmap bmp) {
        int[] bounds = findBoard(bmp);
        if (bounds == null) return new Frame(false, null, 0, 0, 0, true);
        int left = bounds[0], top = bounds[1], size = bounds[2];

        boolean positionStable =
                left == prevLeft && top == prevTop && size == prevSize;
        prevLeft = left; prevTop = top; prevSize = size;

        if (!positionStable) {
            stableHits = 0;
            refValid = false;
            return new Frame(false, null, left, top, size, true);
        }
        stableHits++;
        if (stableHits < SEARCH_STABLE_COUNT) return new Frame(false, null, left, top, size, true);

        int sq = size / 8;
        float[] lum = new float[64];
        char[] pieces = new char[64];

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                int idx = r * 8 + f;
                lum[idx] = squareLuma(bmp, left + f * sq, top + r * sq, sq);
                boolean lightSquare = ((r + f) & 1) == 0;
                // chess.com boards: light squares brighter than dark; classify piece color
                // after occupancy via absolute brightness vs square parity
            }
        }

        if (!refValid) {
            // first stable frame: assume start position emptiness is unknown, so treat
            // the two most extreme parity means as empty refs is wrong; instead we
            // require the user's first frame to be the starting position
            System.arraycopy(lum, 0, emptyRef, 0, 64);
            refValid = true;
            return new Frame(false, null, left, top, size, true);
        }

        float midLuma = boardMidLuma(lum);
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                int idx = r * 8 + f;
                float delta = lum[idx] - emptyRef[idx];
                if (Math.abs(delta) < OCCUPANCY_DELTA) {
                    pieces[idx] = 0; // empty
                    continue;
                }
                boolean lightSquare = ((r + f) & 1) == 0;
                boolean whitePiece = delta > 0;
                // white piece on dark square -> big positive delta; white on light -> small positive
                // black piece on light square -> big negative; black on dark -> small negative
                // side to move alternates each ply; presence flips exactly two squares per move
                pieces[idx] = whitePiece ? 'W' : 'B';
            }
        }

        String fen = buildFen(pieces, left, top, size, sq, bmp);
        return new Frame(fen != null, fen, left, top, size, true);
    }

    private float boardMidLuma(float[] lum) {
        float sum = 0;
        for (float v : lum) sum += v;
        return sum / 64f;
    }

    private float squareLuma(Bitmap bmp, int x, int y, int sq) {
        // sample inner 60% of the square to dodge piece borders and board edge artifacts
        int x0 = x + (int) (sq * 0.2f), y0 = y + (int) (sq * 0.2f);
        int w = (int) (sq * 0.6f), h = (int) (sq * 0.6f);
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
        // scan for the largest run of alternating light/dark 8x8 grid: chess.com board
        // is high-contrast; find the strongest square-color periodicity row/col
        int w = bmp.getWidth(), h = bmp.getHeight();
        int step = Math.max(4, Math.min(w, h) / 64);

        int bestLeft = -1, bestTop = -1, bestSize = -1;
        float bestScore = 0;

        int maxBoard = (int) (Math.min(w, h) * 0.95f);
        int minBoard = (int) (Math.min(w, h) * 0.45f);

        for (int size = maxBoard; size >= minBoard; size -= maxBoard / 12) {
            for (int top = 0; top + size <= h; top += size / 8) {
                for (int left = 0; left + size <= w; left += size / 8) {
                    float score = gridScore(bmp, left, top, size, step);
                    if (score > bestScore) {
                        bestScore = score;
                        bestLeft = left; bestTop = top; bestSize = size;
                    }
                }
            }
        }
        if (bestScore < 30f) return null;
        return new int[]{bestLeft, bestTop, bestSize};
    }

    private float gridScore(Bitmap bmp, int left, int top, int size, int step) {
        int sq = size / 8;
        float contrast = 0;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 7; f++) {
                boolean lightA = ((r + f) & 1) == 0;
                boolean lightB = ((r + f + 1) & 1) == 0;
                if (lightA == lightB) continue;
                float la = squareLuma(bmp, left + f * sq, top + r * sq, sq);
                float lb = squareLuma(bmp, left + (f + 1) * sq, top + r * sq, sq);
                contrast += Math.abs(la - lb);
            }
        }
        contrast /= 56f;
        return contrast;
    }

    private String buildFen(char[] pieces, int left, int top, int size, int sq, Bitmap bmp) {
        // W/B -> piece letters. Piece type from luminance magnitude:
        // pieces darker than square are black, brighter are white; type resolved by
        // edge density inside the square (pawn low, rook/queen high).
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                char p = pieces[r * 8 + f];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                int x = left + f * sq, y = top + r * sq;
                char type = classifyPiece(bmp, x, y, sq, p);
                sb.append(p == 'W' ? Character.toUpperCase(type) : type);
            }
            if (empty > 0) sb.append(empty);
            if (r < 7) sb.append('/');
        }
        sb.append(" w - - 0 1");
        return sb.toString();
    }

    private char classifyPiece(Bitmap bmp, int x, int y, int sq, char color) {
        // edge-density heuristic: count strong luminance transitions in the inner square
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
        if (transitions < 6) return 'P';
        if (transitions < 12) return 'N';
        if (transitions < 18) return 'B';
        if (transitions < 24) return 'R';
        return 'Q';
    }
}
