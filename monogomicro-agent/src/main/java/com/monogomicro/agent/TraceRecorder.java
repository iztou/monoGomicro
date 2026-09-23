package com.monogomicro.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步轨迹记录器：织入代码只做一次入队（队列满直接丢弃，绝不阻塞业务线程），
 * 后台守护线程批量落盘。close hook 保证进程退出前 flush。
 */
public final class TraceRecorder {

    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(200_000);
    private static volatile boolean running = false;

    private TraceRecorder() {
    }

    public static void start(String outFile) {
        if (running) {
            return;
        }
        running = true;
        Thread writer = new Thread(() -> {
            try (BufferedWriter bw = Files.newBufferedWriter(Path.of(outFile), StandardCharsets.UTF_8)) {
                bw.write("# monoGomicro trace v1\n");
                while (running || !QUEUE.isEmpty()) {
                    String line = QUEUE.poll(200, TimeUnit.MILLISECONDS);
                    if (line != null) {
                        bw.write(line);
                        bw.write('\n');
                        if (Math.random() < 0.02) {
                            bw.flush(); // 周期性刷盘，进程被杀也最多丢最近记录
                        }
                    }
                }
                bw.flush();
            } catch (IOException | InterruptedException e) {
                System.err.println("[monogomicro] 轨迹写入失败: " + e);
            }
        }, "monogomicro-trace-writer");
        writer.setDaemon(true);
        writer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            try {
                writer.join(3000);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }, "monogomicro-trace-shutdown"));
    }

    /** 织入点调用。参数必须只读共享状态，保证线程安全且极快。 */
    public static void record(char kind, String fqcn, String method) {
        if (!running) {
            return;
        }
        QUEUE.offer(Thread.currentThread().getId() + "\t" + kind + "\t" + System.nanoTime()
                + "\t" + fqcn + "\t" + method);
    }
}
