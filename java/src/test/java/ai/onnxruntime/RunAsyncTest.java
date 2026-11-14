/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */
package ai.onnxruntime;

import static ai.onnxruntime.TestHelpers.getResourcePath;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.onnxruntime.OrtSession.Result;
import ai.onnxruntime.OrtSession.SessionOptions;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Tests for {@link OrtSession#runAsync}. */
public class RunAsyncTest {

  static {
    // Two carrier threads for the virtual thread test. This only takes effect if no virtual
    // thread has been started in this JVM yet; with more carriers the test is weaker, not flaky.
    if (System.getProperty("jdk.virtualThreadScheduler.parallelism") == null) {
      System.setProperty("jdk.virtualThreadScheduler.parallelism", "2");
    }
  }

  private static final OrtEnvironment env = OrtEnvironment.getEnvironment();

  private static SessionOptions options(int intraOpThreads) throws OrtException {
    SessionOptions options = new SessionOptions();
    options.setIntraOpNumThreads(intraOpThreads);
    return options;
  }

  @Test
  public void runAsyncMatchesRun() throws Exception {
    String modelPath = getResourcePath("/java-matmul.onnx").toString();
    try (SessionOptions options = options(2);
        OrtSession session = env.createSession(modelPath, options);
        OnnxTensor input =
            OnnxTensor.createTensor(env, new float[][] {{1, 2, 3, 4}, {5, 6, 7, 8}})) {
      Map<String, OnnxTensor> inputs = Collections.singletonMap("input", input);
      float[][] expected;
      try (Result result = session.run(inputs)) {
        expected = (float[][]) result.get(0).getValue();
      }
      try (Result result = session.runAsync(inputs).get(10, TimeUnit.SECONDS)) {
        assertEquals(1, result.size());
        float[][] actual = (float[][]) result.get("output").get().getValue();
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
          assertArrayEquals(expected[i], actual[i]);
        }
      }
    }
  }

  @Test
  public void concurrentRunAsync() throws Exception {
    String modelPath = getResourcePath("/java-matmul.onnx").toString();
    int numRuns = 32;
    try (SessionOptions options = options(4);
        OrtSession session = env.createSession(modelPath, options)) {
      List<OnnxTensor> tensors = new ArrayList<>();
      List<CompletableFuture<Result>> futures = new ArrayList<>();
      try {
        for (int i = 0; i < numRuns; i++) {
          OnnxTensor input = OnnxTensor.createTensor(env, new float[][] {{i, 0, 0, 0}});
          tensors.add(input);
          futures.add(session.runAsync(Collections.singletonMap("input", input)));
        }
        for (int i = 0; i < numRuns; i++) {
          try (Result result = futures.get(i).get(10, TimeUnit.SECONDS)) {
            float[][] output = (float[][]) result.get(0).getValue();
            // The first row of the weight matrix is {1, 2, 3, 4}.
            assertArrayEquals(new float[] {i, 2 * i, 3 * i, 4 * i}, output[0]);
          }
        }
      } finally {
        for (OnnxTensor t : tensors) {
          t.close();
        }
      }
    }
  }

  @Test
  public void runAsyncFailureCompletesExceptionally() throws Exception {
    String modelPath = getResourcePath("/java-matmul.onnx").toString();
    try (SessionOptions options = options(2);
        OrtSession session = env.createSession(modelPath, options);
        // The model expects [batch, 4], so a [1, 3] input fails inside the native run.
        OnnxTensor input = OnnxTensor.createTensor(env, new float[][] {{1, 2, 3}})) {
      CompletableFuture<Result> future = session.runAsync(Collections.singletonMap("input", input));
      ExecutionException e =
          assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
      assertInstanceOf(OrtException.class, e.getCause());
    }
  }

  @Test
  public void runAsyncNeedsIntraOpThreads() throws Exception {
    String modelPath = getResourcePath("/java-matmul.onnx").toString();
    try (SessionOptions options = options(1);
        OrtSession session = env.createSession(modelPath, options);
        OnnxTensor input = OnnxTensor.createTensor(env, new float[][] {{1, 2, 3, 4}})) {
      OrtException e =
          assertThrows(
              OrtException.class, () -> session.runAsync(Collections.singletonMap("input", input)));
      assertTrue(e.getMessage().contains("intra op thread pool"), e.getMessage());
      // The failed call must not leave a pending run behind, otherwise close() would throw.
    }
  }

  @Test
  public void closeWithPendingRunThrows() throws Exception {
    try (SessionOptions options = options(2);
        OrtSession session = SlowModel.createSession(env, options);
        OnnxTensor input = SlowModel.input(env)) {
      CompletableFuture<Result> future = session.runAsync(SlowModel.feed(input));
      try {
        if (!future.isDone()) {
          assertThrows(IllegalStateException.class, session::close);
        }
      } finally {
        future.get(60, TimeUnit.SECONDS).close();
      }
    }
  }

  @Test
  public void cancelTerminatesOwnedRun() throws Exception {
    try (SessionOptions options = options(2);
        OnnxTensor input = SlowModel.input(env)) {
      OrtSession session = SlowModel.createSession(env, options);
      long fullRunNanos = SlowModel.timeRun(session, input);
      assumeTrue(fullRunNanos > TimeUnit.MILLISECONDS.toNanos(200), "model too fast to cancel");

      long start = System.nanoTime();
      CompletableFuture<Result> future = session.runAsync(SlowModel.feed(input));
      Thread.sleep(TimeUnit.NANOSECONDS.toMillis(fullRunNanos) / 10);
      assertTrue(future.cancel(true));
      assertTrue(future.isCancelled());

      // The native run stops at the next node boundary; wait until the session can be closed.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (true) {
        try {
          session.close();
          break;
        } catch (IllegalStateException e) {
          assertTrue(System.nanoTime() < deadline, "async run never finished");
          Thread.sleep(1);
        }
      }
      long drained = System.nanoTime() - start;
      assertTrue(
          drained < fullRunNanos * 3 / 4,
          "cancelled run took " + drained / 1_000_000 + "ms, full run " + fullRunNanos / 1_000_000);
    }
  }

  /**
   * Starts one virtual thread per carrier which waits on {@code runAsync().join()}, then checks
   * that another virtual thread still gets a carrier while all of the runs are in flight. With
   * {@code run()} the carriers would be pinned in native code until the runs finish.
   */
  @Test
  public void virtualThreadReleasesCarrierWhileWaiting() throws Exception {
    Method startVirtualThread;
    try {
      startVirtualThread = Thread.class.getMethod("startVirtualThread", Runnable.class);
    } catch (NoSuchMethodException e) {
      assumeTrue(false, "virtual threads need Java 21+");
      return;
    }
    int carriers = Integer.parseInt(System.getProperty("jdk.virtualThreadScheduler.parallelism"));
    // Each async run occupies one intra-op worker, so give the pool one worker per carrier.
    try (SessionOptions options = options(carriers + 1);
        OrtSession session = SlowModel.createSession(env, options);
        OnnxTensor input = SlowModel.input(env)) {
      long fullRunNanos = SlowModel.timeRun(session, input);
      assumeTrue(fullRunNanos > TimeUnit.MILLISECONDS.toNanos(200), "model too fast");

      List<CompletableFuture<Result>> futures = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch started = new CountDownLatch(carriers);
      List<Thread> waiters = new ArrayList<>();
      AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
      for (int i = 0; i < carriers; i++) {
        Runnable waiter =
            () -> {
              try {
                CompletableFuture<Result> future = session.runAsync(SlowModel.feed(input));
                futures.add(future);
                started.countDown();
                future.join().close();
              } catch (Throwable t) {
                waiterFailure.set(t);
                started.countDown();
              }
            };
        waiters.add((Thread) startVirtualThread.invoke(null, waiter));
      }
      assertTrue(started.await(10, TimeUnit.SECONDS));

      AtomicBoolean allPendingWhenProbeRan = new AtomicBoolean(false);
      Runnable probe =
          () -> {
            boolean pending = true;
            synchronized (futures) {
              for (CompletableFuture<Result> f : futures) {
                pending &= !f.isDone();
              }
            }
            allPendingWhenProbeRan.set(pending && futures.size() == carriers);
          };
      Thread probeThread = (Thread) startVirtualThread.invoke(null, probe);
      probeThread.join(TimeUnit.NANOSECONDS.toMillis(fullRunNanos) * 10);
      for (Thread t : waiters) {
        t.join(60_000);
      }
      if (waiterFailure.get() != null) {
        throw new AssertionError(waiterFailure.get());
      }
      assertTrue(
          allPendingWhenProbeRan.get(),
          "probe virtual thread did not run while the async runs were pending");
    }
  }

  /** A model which repeats a square MatMul so that a run takes a noticeable time. */
  static final class SlowModel {
    static final int SIZE = 512;
    static final long TARGET_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static int depth = 0;

    /**
     * Creates a session whose run takes roughly {@link #TARGET_NANOS}. MatMul speed varies by two
     * orders of magnitude across CPUs, so the depth is calibrated once per JVM.
     */
    static synchronized OrtSession createSession(OrtEnvironment env, SessionOptions options)
        throws OrtException {
      if (depth == 0) {
        int probeDepth = 16;
        try (OrtSession probe = env.createSession(bytes(probeDepth), options);
            OnnxTensor input = input(env)) {
          timeRun(probe, input); // warm up
          long nanos = Math.max(1, timeRun(probe, input));
          depth = (int) Math.max(probeDepth, Math.min(4096, probeDepth * TARGET_NANOS / nanos));
        }
      }
      return env.createSession(bytes(depth), options);
    }

    static byte[] bytes(int depth) {
      OnnxMl.GraphProto.Builder graph = OnnxMl.GraphProto.newBuilder();
      graph.setName("ort-test-slow-matmul");
      OnnxMl.TypeProto type =
          ModelGenerators.buildTensorTypeNode(
              new long[] {-1, -1}, new String[] {"n", "n"}, OnnxMl.TensorProto.DataType.FLOAT);
      graph.addInput(OnnxMl.ValueInfoProto.newBuilder().setName("x").setType(type));
      graph.addOutput(OnnxMl.ValueInfoProto.newBuilder().setName("y").setType(type));
      String previous = "x";
      for (int i = 0; i < depth; i++) {
        String out = i == depth - 1 ? "y" : "h" + i;
        graph.addNode(
            OnnxMl.NodeProto.newBuilder()
                .setName("matmul-" + i)
                .setOpType("MatMul")
                .addInput(previous)
                .addInput("x")
                .addOutput(out));
        previous = out;
      }
      OnnxMl.ModelProto.Builder model = OnnxMl.ModelProto.newBuilder();
      model.setGraph(graph);
      model.setIrVersion(8);
      model.addOpsetImport(OnnxMl.OperatorSetIdProto.newBuilder().setVersion(18).build());
      return model.build().toByteArray();
    }

    static OnnxTensor input(OrtEnvironment env) throws OrtException {
      float[][] data = new float[SIZE][SIZE];
      for (float[] row : data) {
        Arrays.fill(row, 1.0f / SIZE);
      }
      return OnnxTensor.createTensor(env, data);
    }

    static Map<String, OnnxTensor> feed(OnnxTensor input) {
      return Collections.singletonMap("x", input);
    }

    static long timeRun(OrtSession session, OnnxTensor input) throws OrtException {
      long start = System.nanoTime();
      try (Result r = session.run(feed(input))) {
        assertFalse(r.size() == 0);
      }
      return System.nanoTime() - start;
    }
  }
}
