package com.ofx.chessassistant;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Bundled stockfish binary from app assets, exec'd directly.
 * Speaks UCI over stdin/stdout.
 */
public class StockfishEngine implements AutoCloseable {

    private final Process process;
    private final java.io.BufferedReader out;
    private final java.io.BufferedWriter in;

    public StockfishEngine(Context ctx) throws IOException {
        File bin = new File(ctx.getFilesDir(), "stockfish");
        if (!bin.exists() || bin.length() == 0) {
            copyAsset(ctx, "stockfish", bin);
            bin.setExecutable(true, true);
        }
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath());
        pb.redirectErrorStream(false);
        process = pb.start();
        out = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
        in = new java.io.BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream()));
        send("uci");
        waitLine("uciok", 5000);
        send("setoption name Skill Level value 20");
        send("setoption name UCI_AnalyseMode value true");
        send("isready");
        waitLine("readyok", 5000);
    }

    private static void copyAsset(Context ctx, String name, File target) throws IOException {
        try (InputStream is = ctx.getAssets().open(name);
             OutputStream os = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private void send(String cmd) throws IOException {
        in.write(cmd);
        in.write("\n");
        in.flush();
    }

    private boolean waitLine(String token, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String line = out.readLine();
            if (line == null) return false;
            if (line.contains(token)) return true;
        }
        return false;
    }

    /** @param movetimeMs engine think time per position */
    public synchronized String bestMove(String fen, long movetimeMs) {
        try {
            send("position fen " + fen);
            send("go movetime " + movetimeMs);
            long deadline = System.currentTimeMillis() + movetimeMs + 4000;
            String best = null;
            while (System.currentTimeMillis() < deadline) {
                String line = out.readLine();
                if (line == null) break;
                if (line.startsWith("bestmove")) {
                    String[] parts = line.split("\\s+");
                    best = parts.length > 1 ? parts[1] : "none";
                    break;
                }
            }
            return best;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            send("quit");
        } catch (IOException ignored) {
        }
        process.destroyForcibly();
    }
}
