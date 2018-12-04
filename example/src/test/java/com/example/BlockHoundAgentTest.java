package com.example;

import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.*;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class BlockHoundAgentTest {

    @Parameters(name = "{index}: {0}")
    public static Iterable<Object[]> data() {
        Map<String, Callable<Mono<?>>> tests = new HashMap<String, Callable<Mono<?>>>();

        tests.put("java.net.Socket#connect", () -> {
            var socket = new Socket();
            var inetSocketAddress = new InetSocketAddress("www.google.com", 80);
            return Mono.fromCallable(() -> {
                socket.connect(inetSocketAddress);
                return "";
            });
        });

        tests.put("java.net.SocketInputStream#socketRead0", () -> {
            var socket = new Socket("www.google.com", 80);
            socket.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes());
            return Mono.fromCallable(() -> {
                return socket.getInputStream().read(new byte[1]);
            });
        });

        tests.put("java.net.SocketOutputStream#socketWrite0", () -> {
            var socket = new Socket("www.google.com", 80);
            return Mono.fromCallable(() -> {
                socket.getOutputStream().write(1);
                return null;
            });
        });

        tests.put("jdk.internal.misc.Unsafe#park", () -> {
            return Mono.fromCallable(() -> {
                Thread thread = Thread.currentThread();
                Mono.delay(Duration.ofMillis(100)).subscribe(__ -> LockSupport.unpark(thread));
                LockSupport.park();
                return "";
            });
        });

        tests.put("java.lang.Object#wait", () -> {
            return Mono.fromCallable(() -> {
                Object lock = new Object();
                synchronized (lock) {
                    lock.wait(10);
                }
                return null;
            });
        });

        tests.put("java.lang.Thread.sleep", () -> {
            return Mono.fromCallable(() -> {
                Thread.sleep(10);
                return "";
            });
        });

        tests.put("java.lang.Thread.yield", () -> {
            return Mono.fromCallable(() -> {
                Thread.yield();
                return "";
            });
        });

        tests.put("java.lang.Thread.onSpinWait", () -> {
            return Mono.fromCallable(() -> {
                Thread.onSpinWait();
                return "";
            });
        });

        tests.put("java.io.FileOutputStream#write", () -> {
            var file = File.createTempFile("test", "");
            var fileOutputStream = new FileOutputStream(file);
            return Mono.fromCallable(() -> {
                fileOutputStream.write(1);
                return "";
            });
        });

        tests.put("java.io.FileInputStream#read0", () -> {
            var file = File.createTempFile("test", "");
            var fileOutputStream = new FileInputStream(file);
            return Mono.fromCallable(() -> {
                fileOutputStream.read();
                return "";
            });
        });

        tests.put("java.io.RandomAccessFile#read0", () -> {
            var randomAccessFile = new RandomAccessFile(File.createTempFile("test", ""), "rw");
            return Mono.fromCallable(() -> {
                randomAccessFile.read();
                return "";
            });
        });

        tests.put("java.io.RandomAccessFile#write0", () -> {
            var randomAccessFile = new RandomAccessFile(File.createTempFile("test", ""), "rw");
            return Mono.fromCallable(() -> {
                randomAccessFile.write(1);
                return "";
            });
        });

        tests.put("java.io.RandomAccessFile#writeBytes", () -> {
            var randomAccessFile = new RandomAccessFile(File.createTempFile("test", ""), "rw");
            return Mono.fromCallable(() -> {
                randomAccessFile.writeBytes("");
                return "";
            });
        });

        tests.put("java.lang.ProcessImpl#forkAndExec", () -> {
            var processBuilder = new ProcessBuilder("java");
            return Mono.fromCallable(() -> {
                processBuilder.start();
                return "";
            });
        });

        tests.put("java.net.DatagramSocket#connect", () -> {
            var socket = new DatagramSocket();
            return Mono.fromCallable(() -> {
                socket.connect(InetAddress.getLoopbackAddress(), 0);
                return "";
            });
        });

        tests.put("java.net.PlainDatagramSocketImpl#send", () -> {
            var socket = new DatagramSocket();
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            return Mono.fromCallable(() -> {
                socket.send(new DatagramPacket(new byte[1], 1));
                return "";
            });
        });

        tests.put("java.net.PlainDatagramSocketImpl#peekData", () -> {
            var socket = new DatagramSocket();
            socket.setSoTimeout(10);
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            return Mono.fromCallable(() -> {
                socket.receive(new DatagramPacket(new byte[1], 1));
                return "";
            }).onErrorReturn(SocketTimeoutException.class, "");
        });

        tests.put("java.net.PlainSocketImpl#socketAccept", () -> {
            var socket = new ServerSocket(0);
            socket.setSoTimeout(100);
            return Mono.fromCallable(() -> {
                socket.accept();
                return "";
            }).onErrorReturn(SocketTimeoutException.class, "");
        });

        // tests.entrySet().removeIf(it -> !"java.lang.Thread.sleep".equals(it.getKey()));

        return tests.entrySet().stream().map(it -> new Object[]{it.getKey(), it.getValue()}).collect(Collectors.toList());
    }

    @Parameter(0)
    public String method;

    @Parameter(1)
    public Callable<Mono<?>> publisher;

    @Rule
    public Timeout timeout = new Timeout(2, TimeUnit.SECONDS);

    @Test
    public void positive() throws Exception {
        publisher.call().hide().subscribeOn(Schedulers.elastic()).block(Duration.ofSeconds(1));
    }

    @Test
    public void negative() throws Exception {
        var mono = publisher.call().hide();
        var e = Assertions.catchThrowable(() -> {
            mono.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(1));
        });
        assertThat(e).isNotNull();

        e.printStackTrace(System.out);

        assertThat(e).hasMessageEndingWith("Blocking call! " + method);
    }

    @Test
    public void negativeWithFlux() throws Exception {
        var mono = Flux.from(publisher.call()).hide();
        var e = Assertions.catchThrowable(() -> {
            mono.subscribeOn(Schedulers.parallel()).blockLast(Duration.ofSeconds(1));
        });
        assertThat(e).isNotNull();

        e.printStackTrace(System.out);

        assertThat(e).hasMessageEndingWith("Blocking call! " + method);
    }

}