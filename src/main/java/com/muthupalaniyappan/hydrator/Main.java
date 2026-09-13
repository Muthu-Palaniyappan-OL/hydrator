package com.muthupalaniyappan.hydrator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class Main {

  private static final long BLOCK_SIZE = 512L * 1024;
  private static final int DEFAULT_IOPS = 3000;
  private static final String VERSION = loadVersion();

  static void main(String[] args) throws Exception {
    Options options = parseOptions(args);
    if (options == null) {
      return;
    }

    try (AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(options.path,
        StandardOpenOption.READ)) {
      long fileSize = fileChannel.size();
      long blockCount = fileSize / BLOCK_SIZE + (fileSize % BLOCK_SIZE == 0 ? 0 : 1);

      ReadController controller = new ReadController(fileChannel, blockCount, options.iops);
      controller.start();
      controller.await();
    }
  }

  private static String loadVersion() {
    try (InputStream input = Main.class.getResourceAsStream("/version.properties")) {
      if (input == null) {
        return "development";
      }
      java.util.Properties properties = new java.util.Properties();
      properties.load(input);
      return properties.getProperty("version", "development");
    } catch (IOException exception) {
      return "development";
    }
  }

  private static Options parseOptions(String[] args) {
    if (args.length == 0) {
      printHelp();
      return null;
    }

    int iops = DEFAULT_IOPS;
    Path path = null;
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if ("--help".equals(argument) || "-h".equals(argument)) {
        printHelp();
        return null;
      }
      if ("--version".equals(argument) || "-v".equals(argument)) {
        System.out.println("hydrator-java " + VERSION);
        return null;
      }
      if ("--iops".equals(argument)) {
        if (++index >= args.length) {
          throw new IllegalArgumentException("--iops requires a positive integer");
        }
        iops = parseIops(args[index]);
        continue;
      }
      if (argument.startsWith("--iops=")) {
        iops = parseIops(argument.substring("--iops=".length()));
        continue;
      }
      if ("--file".equals(argument)) {
        if (++index >= args.length) {
          throw new IllegalArgumentException("--file requires a path");
        }
        path = Paths.get(args[index]);
        continue;
      }
      if (argument.startsWith("-")) {
        throw new IllegalArgumentException("Unknown option: " + argument);
      }
      if (path != null) {
        throw new IllegalArgumentException("Only one file path may be supplied");
      }
      path = Paths.get(argument);
    }

    if (path == null) {
      throw new IllegalArgumentException("A file path is required; use --help for usage");
    }
    return new Options(iops, path);
  }

  private static void printHelp() {
    System.out.println("Usage: hydrator-java [options] <file>");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --iops <number>  Target completed reads per second (default: 3000)");
    System.out.println("  --file <path>    File to sample; positional form is also supported");
    System.out.println("  --help           Show this help message");
    System.out.println("  --version        Show the hydrator version");
    System.out.println();
    System.out.println("One byte is read from the beginning of every 512 KiB block.");
  }

  private static int parseIops(String value) {
    try {
      int iops = Integer.parseInt(value);
      if (iops <= 0) {
        throw new IllegalArgumentException("IOPS must be greater than zero: " + value);
      }
      return iops;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("IOPS must be a positive integer: " + value, exception);
    }
  }

  private static final class Options {

    private final int iops;
    private final Path path;

    private Options(int iops, Path path) {
      this.iops = iops;
      this.path = path;
    }
  }

  private static final class ReadController implements CompletionHandler<Integer, ReadRequest> {

    private final AsynchronousFileChannel fileChannel;
    private final long blockCount;
    private final int targetIops;
    private final AtomicLong nextBlock = new AtomicLong();
    private final LongAdder inFlight = new LongAdder();
    private final AtomicLong completedThisSecond = new AtomicLong();
    private final LongAdder completedTotal = new LongAdder();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final CountDownLatch finishedLatch = new CountDownLatch(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private ReadController(AsynchronousFileChannel fileChannel, long blockCount, int targetIops) {
      this.fileChannel = fileChannel;
      this.blockCount = blockCount;
      this.targetIops = targetIops;
    }

    private void start() {
      submitReads(targetIops);
      if (nextBlock.get() < blockCount) {
        scheduler.scheduleAtFixedRate(this::replenish, 1, 1, TimeUnit.SECONDS);
      } else {
        checkFinished();
      }
    }

    private void replenish() {
      if (finished.get() || failure.get() != null) {
        return;
      }

      long completed = completedThisSecond.getAndSet(0);
      long deficit = Math.max(0, targetIops - completed);
      int submitted = submitReads(deficit);
      System.out.printf("completed=%d, launching=%d%n", completed, submitted);

      if (nextBlock.get() >= blockCount) {
        scheduler.shutdown();
      }
      checkFinished();
    }

    private int submitReads(long count) {
      int submitted = 0;
      while (submitted < count) {
        inFlight.increment();
        long block = nextBlock.getAndIncrement();
        if (block >= blockCount) {
          inFlight.decrement();
          nextBlock.set(blockCount);
          break;
        }

        ReadRequest request = new ReadRequest(ByteBuffer.allocate(1));
        try {
          // This CompletionHandler overload submits the read and returns immediately.
          fileChannel.read(request.buffer, block * BLOCK_SIZE, request, this);
          submitted++;
        } catch (RuntimeException exception) {
          inFlight.decrement();
          failed(exception);
          break;
        }
      }
      return submitted;
    }

    private void checkFinished() {
      if (nextBlock.get() >= blockCount && inFlight.sum() == 0
          && finished.compareAndSet(false, true)) {
        scheduler.shutdownNow();
        finishedLatch.countDown();
      }
    }

    private void await() throws IOException, InterruptedException {
      finishedLatch.await();
      Throwable exception = failure.get();
      if (exception != null) {
        throw new IOException("An asynchronous file read failed", exception);
      }
      System.out.println("completed total=" + completedTotal.sum());
    }

    @Override
    public void completed(Integer bytesRead, ReadRequest request) {
      if (bytesRead != 1) {
        failed(new IOException("Expected one byte but read " + bytesRead + " bytes"));
        return;
      }

      inFlight.decrement();
      completedThisSecond.incrementAndGet();
      completedTotal.increment();
      checkFinished();
    }

    @Override
    public void failed(Throwable exception, ReadRequest request) {
      inFlight.decrement();
      failed(exception);
    }

    private void failed(Throwable exception) {
      if (failure.compareAndSet(null, exception)) {
        scheduler.shutdownNow();
        finished.set(true);
        finishedLatch.countDown();
      }
    }
  }

  private static final class ReadRequest {

    private final ByteBuffer buffer;

    private ReadRequest(ByteBuffer buffer) {
      this.buffer = buffer;
    }
  }
}
