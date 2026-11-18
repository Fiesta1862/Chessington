package com.ofx.chessassistant;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

public class ManualBoardActivity extends Activity {

    public static final String PREFS = "chessington";
    public static final String KEY_LEFT = "board_left";
    public static final String KEY_TOP = "board_top";
    public static final String KEY_SIZE = "board_size";

    private static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1";

    private EditText fenField;
    private TextView status;
    private TextView lastMove;

    private char[] board = new char[64]; // row 0 = rank 8, col 0 = file a
    private char engineColor = 'b';
    private char sideToMove = 'w';

    private StockfishEngine engine;
    private Thread thinkThread;

    private int selectedIndex = -1;
    private String opponentFrom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manual);

        fenField = findViewById(R.id.fen);
        status = findViewById(R.id.status);
        lastMove = findViewById(R.id.last_move);
        fenField.setText(START_FEN);

        try {
            engine = new StockfishEngine(this);
        } catch (Exception e) {
            status.setText("engine failed: " + e.getMessage());
        }

        Button load = findViewById(R.id.btn_load);
        Button white = findViewById(R.id.btn_white);
        Button black = findViewById(R.id.btn_black);
        Button clear = findViewById(R.id.btn_clear);
        Button calibrate = findViewById(R.id.btn_calibrate);
        GridLayout grid = findViewById(R.id.board);

        load.setOnClickListener(v -> loadFen(fenField.getText().toString()));
        white.setOnClickListener(v -> setEngineColor('w'));
        black.setOnClickListener(v -> setEngineColor('b'));
        clear.setOnClickListener(v -> {
            selectedIndex = -1;
            opponentFrom = null;
            status.setText("Selection cleared");
        });
        calibrate.setOnClickListener(v ->
                startActivity(new Intent(this, CalibrationActivity.class)));

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Button sq = new Button(this);
                sq.setTag(r * 8 + c);
                sq.setTextSize(11f);
                sq.setPadding(0, 0, 0, 0);
                grid.addView(sq, new GridLayout.LayoutParams(
                        GridLayout.spec(r, 1f), GridLayout.spec(c, 1f)));
                sq.setOnClickListener(this::onSquare);
            }
        }
        loadFen(START_FEN);
        setEngineColor('b');
    }

    private void setEngineColor(char c) {
        engineColor = c;
        status.setText("Assistant plays " + (c == 'w' ? "WHITE" : "BLACK")
                + " — tap opponent's move first");
    }

    private void onSquare(View v) {
        int idx = (int) v.getTag();
        if (selectedIndex < 0) {
            if (board[idx] == 0) {
                status.setText("Empty square — pick a piece");
                return;
            }
            selectedIndex = idx;
            status.setText("From: " + name(idx) + " — tap destination");
            return;
        }
        if (idx == selectedIndex) {
            selectedIndex = -1;
            status.setText("Selection cleared");
            return;
        }

        String uci = name(selectedIndex) + name(idx);
        selectedIndex = -1;

        if (sideToMove != engineColor) {
            // this was the opponent's move — apply it, then ask the engine
            opponentFrom = uci;
            applyUci(uci);
            if (engine == null) {
                status.setText("engine unavailable");
                return;
            }
            if (thinkThread != null && thinkThread.isAlive()) return;
            thinkThread = new Thread(() -> {
                String fen = toFen();
                String best = engine.bestMove(fen, 500);
                runOnUiThread(() -> {
                    if (best == null || best.length() < 4) {
                        status.setText("engine returned no move");
                        return;
                    }
                    applyUci(best);
                    lastMove.setText("opponent " + opponentFrom + "  •  engine " + best);
                    status.setText("Played " + best + " on screen");
                    fenField.setText(toFen());
                    render();
                    // get out of the way so the gesture lands on chess.com, not our own buttons
                    moveTaskToBack(true);
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> playOnScreen(best), 500);
                });
            }, "stockfish-think");
            thinkThread.start();
            status.setText("thinking…");
        } else {
            status.setText("It's the assistant's turn — wait for the opponent");
        }
        render();
    }

    private void loadFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 2) {
            status.setText("bad FEN");
            return;
        }
        String[] rows = parts[0].split("/");
        if (rows.length != 8) {
            status.setText("bad FEN: need 8 ranks");
            return;
        }
        for (int r = 0; r < 8; r++) {
            int col = 0;
            for (char ch : rows[r].toCharArray()) {
                if (Character.isDigit(ch)) {
                    col += ch - '0';
                } else if (col < 8) {
                    board[r * 8 + col] = ch;
                    col++;
                }
            }
        }
        sideToMove = parts[1].charAt(0);
        lastMove.setText("—");
        status.setText("Loaded — set engine color, then tap opponent's move");
        render();
    }

    private String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int c = 0; c < 8; c++) {
                char p = board[r * 8 + c];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                sb.append(p);
            }
            if (empty > 0) sb.append(empty);
            if (r < 7) sb.append('/');
        }
        sb.append(' ').append(sideToMove).append(" - - 0 1");
        return sb.toString();
    }

    /** Apply a UCI move to the internal board. No legality check — Olivia drives. */
    private void applyUci(String uci) {
        if (uci.length() < 4) return;
        int from = squareIndex(uci.substring(0, 2));
        int to = squareIndex(uci.substring(2, 4));
        if (from < 0 || to < 0) return;

        char piece = board[from];
        boolean capture = board[to] != 0;
        boolean enPassant = piece != 0 && Character.toUpperCase(piece) == 'P'
                && uci.charAt(0) != uci.charAt(2) && board[to] == 0;

        // castling: move the rook too
        if (Character.toUpperCase(piece) == 'K' && Math.abs((uci.charAt(0) - uci.charAt(2))) == 2) {
            int rookFrom, rookTo;
            if (uci.charAt(2) == 'g') { rookFrom = to + 1; rookTo = to - 1; }
            else { rookFrom = to - 2; rookTo = to + 1; }
            board[rookTo] = board[rookFrom];
            board[rookFrom] = 0;
        }

        board[to] = piece;
        board[from] = 0;
        if (enPassant) {
            int capRow = (from / 8) + (Character.isUpperCase(piece) ? 1 : -1);
            board[capRow * 8 + (to % 8)] = 0;
        }
        if (uci.length() == 5 && Character.toUpperCase(piece) == 'P') {
            board[to] = Character.isUpperCase(piece)
                    ? Character.toUpperCase(uci.charAt(4))
                    : Character.toLowerCase(uci.charAt(4));
        }
        sideToMove = (char) ('w' + 'b' - sideToMove);
    }

    private void render() {
        GridLayout grid = findViewById(R.id.board);
        for (int i = 0; i < grid.getChildCount(); i++) {
            Button b = (Button) grid.getChildAt(i);
            char p = board[i];
            b.setText(p == 0 ? "" : String.valueOf(p));
            boolean light = ((i / 8) + (i % 8) & 1) == 0;
            b.setBackgroundColor(light ? 0xFFDEE3E6 : 0xFF8CA2AD);
            b.setTextColor(Character.isUpperCase(p) ? 0xFFFFFFFF : 0xFF111111);
            b.setAlpha(i == selectedIndex ? 0.55f : 1f);
        }
    }

    private void playOnScreen(String uci) {
        if (!MoveAccessibilityService.hasInstance()) {
            status.setText("move " + uci + " — enable accessibility to auto-play");
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int left = prefs.getInt(KEY_LEFT, -1);
        int top = prefs.getInt(KEY_TOP, -1);
        int size = prefs.getInt(KEY_SIZE, -1);
        if (left < 0 || size <= 0) {
            status.setText("move " + uci + " — calibrate the board first");
            return;
        }
        MoveAccessibilityService.performMove(squareRect(uci.substring(0, 2), left, top, size),
                squareRect(uci.substring(2, 4), left, top, size));
    }

    /** white engine -> white at bottom; black engine -> black at bottom (flipped board) */
    private android.graphics.RectF squareRect(String square, int left, int top, int size) {
        int file = square.charAt(0) - 'a';
        int rank = square.charAt(1) - '0';
        int row = engineColor == 'w' ? 8 - rank : rank - 1;
        int col = engineColor == 'w' ? file : 7 - file;
        float sq = size / 8f;
        float x = left + col * sq;
        float y = top + row * sq;
        return new android.graphics.RectF(x, y, x + sq, y + sq);
    }

    private static int squareIndex(String square) {
        if (square.length() < 2) return -1;
        int f = square.charAt(0) - 'a';
        int r = 8 - (square.charAt(1) - '0');
        if (f < 0 || f > 7 || r < 0 || r > 7) return -1;
        return r * 8 + f;
    }

    private static String name(int idx) {
        return String.valueOf((char) ('a' + (idx % 8))) + (8 - idx / 8);
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.close();
        super.onDestroy();
    }
}
