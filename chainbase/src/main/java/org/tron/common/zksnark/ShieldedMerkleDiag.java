package org.tron.common.zksnark;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;

/** Diagnostic-only probe. Added only by .opencode/shielded-merkle-ci-ab patches. */
public final class ShieldedMerkleDiag {

  private static volatile ChainBaseManager testManager;
  private static volatile Object snapshotManager;

  private ShieldedMerkleDiag() {
  }

  public static void bindTestContext(ChainBaseManager manager, Object snapshot) {
    testManager = manager;
    snapshotManager = snapshot;
  }

  public static boolean anchorLookup(String stage, byte[] anchor, ChainBaseManager actuatorManager,
      MerkleContainer container) {
    event(stage + "-before", anchor, actuatorManager, container, null);
    boolean contains = container.merkleRootExist(anchor);
    event(stage + "-after", anchor, actuatorManager, container, Boolean.toString(contains));
    return contains;
  }

  public static void anchorPut(byte[] anchor, MerkleContainer container) {
    event("A-put-after", anchor, null, container, safeContains(container, anchor));
  }

  public static void event(String stage, byte[] anchor, ChainBaseManager actuatorManager,
      MerkleContainer container, String contains) {
    try {
      ChainBaseManager tm = testManager;
      Object store = container == null ? null : container.getIncrementalMerkleTreeStore();
      Object amContainer = actuatorManager == null ? null : actuatorManager.getMerkleContainer();
      Object amStore = amContainer == null ? null
          : ((MerkleContainer) amContainer).getIncrementalMerkleTreeStore();
      Object sm = snapshotFromStore(store);
      if (sm == null) {
        sm = snapshotManager;
      }
      String line = "nanoTime=" + System.nanoTime()
          + " currentTime=" + System.currentTimeMillis()
          + " thread=" + clean(Thread.currentThread().getName())
          + " pid=" + pid()
          + " worker=" + clean(System.getProperty("org.gradle.test.worker", "unknown"))
          + " stage=" + clean(stage)
          + " anchor=" + (anchor == null ? "null" : ByteArray.toHexString(anchor))
          + " testManager=" + identity(tm)
          + " actuatorManager=" + identity(actuatorManager)
          + " container=" + identity(container)
          + " store=" + identity(store)
          + " actuatorContainer=" + identity(amContainer)
          + " actuatorStore=" + identity(amStore)
          + " contains=" + (contains == null ? "not-read" : clean(contains))
          + " chainHead=" + chainHead(actuatorManager == null ? tm : actuatorManager)
          + " snapshotManager=" + identity(sm)
          + " snapshotActiveSession=" + reflectValue(sm, "getActiveSession", "activeSession")
          + " snapshotDepth=" + reflectValue(sm, "size", "size");
      append(line);
    } catch (Throwable t) {
      diagnosticError(stage, t);
    }
  }

  private static String safeContains(MerkleContainer container, byte[] anchor) {
    try {
      return Boolean.toString(container != null && anchor != null && container.merkleRootExist(anchor));
    } catch (Throwable t) {
      return "error:" + t.getClass().getName();
    }
  }

  private static Object snapshotFromStore(Object store) {
    try {
      Class<?> type = store == null ? null : store.getClass();
      while (type != null) {
        try {
          Field field = type.getDeclaredField("revokingDatabase");
          field.setAccessible(true);
          return field.get(store);
        } catch (NoSuchFieldException e) {
          type = type.getSuperclass();
        }
      }
    } catch (Throwable t) {
      diagnosticError("snapshot-reflection", t);
    }
    return null;
  }

  private static String reflectValue(Object target, String methodName, String fieldName) {
    if (target == null) {
      return "unavailable";
    }
    try {
      Class<?> type = target.getClass();
      while (type != null) {
        try {
          Method method = type.getDeclaredMethod(methodName);
          method.setAccessible(true);
          return clean(String.valueOf(method.invoke(target)));
        } catch (NoSuchMethodException e) {
          type = type.getSuperclass();
        }
      }
      type = target.getClass();
      while (type != null) {
        try {
          Field field = type.getDeclaredField(fieldName);
          field.setAccessible(true);
          return clean(String.valueOf(field.get(target)));
        } catch (NoSuchFieldException e) {
          type = type.getSuperclass();
        }
      }
      return "unavailable";
    } catch (Throwable t) {
      diagnosticError("snapshot-" + methodName, t);
      return "diagnosticError:" + t.getClass().getSimpleName();
    }
  }

  private static String chainHead(ChainBaseManager manager) {
    try {
      return manager == null ? "unavailable"
          : manager.getHeadBlockNum() + ":" + manager.getHeadBlockId().toString();
    } catch (Throwable t) {
      diagnosticError("chain-head", t);
      return "diagnosticError:" + t.getClass().getSimpleName();
    }
  }

  private static String identity(Object value) {
    return value == null ? "null" : value.getClass().getName() + "@"
        + Integer.toHexString(System.identityHashCode(value));
  }

  private static String pid() {
    try {
      String value = ManagementFactory.getRuntimeMXBean().getName();
      int at = value.indexOf('@');
      return at < 0 ? value : value.substring(0, at);
    } catch (Throwable t) {
      return "unknown";
    }
  }

  private static String clean(String value) {
    return value == null ? "null" : value.replace('\n', ' ').replace('\r', ' ').replace(' ', '_');
  }

  private static void diagnosticError(String stage, Throwable t) {
    try {
      append("nanoTime=" + System.nanoTime() + " currentTime=" + System.currentTimeMillis()
          + " thread=" + clean(Thread.currentThread().getName()) + " pid=" + pid()
          + " worker=" + clean(System.getProperty("org.gradle.test.worker", "unknown"))
          + " stage=" + clean(stage) + " diagnosticError="
          + clean(t.getClass().getName() + ":" + t.getMessage()));
    } catch (Throwable ignored) {
      // Probe must not affect test behavior.
    }
  }

  private static void append(String line) throws Exception {
    String directory = System.getProperty("shielded.diag.dir");
    if (directory == null || directory.trim().isEmpty()) {
      return;
    }
    File dir = new File(directory);
    if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
      throw new IllegalStateException("cannot create " + dir);
    }
    String worker = clean(System.getProperty("org.gradle.test.worker", "unknown"));
    File output = new File(dir, "shielded-merkle-" + worker + "-p" + pid() + ".log");
    byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    try (FileChannel channel = FileChannel.open(output.toPath(), StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(ByteBuffer.wrap(bytes));
    }
  }
}
