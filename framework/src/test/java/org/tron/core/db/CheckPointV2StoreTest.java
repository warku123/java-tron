package org.tron.core.db;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.RocksDB;
import org.tron.common.storage.WriteOptionsWrapper;
import org.tron.core.Constant;
import org.tron.core.config.args.Args;
import org.tron.core.store.CheckPointV2Store;

public class CheckPointV2StoreTest {

  @ClassRule
  public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

  static {
    RocksDB.loadLibrary();
  }

  @BeforeClass
  public static void initArgs() throws IOException {
    Args.setParam(new String[]{"-d", temporaryFolder.newFolder().toString()}, Constant.TEST_CONF);
  }

  @AfterClass
  public static void destroy() {
    Args.clearParam();
  }

  @Test
  public void testCloseCallsSuperClose() throws Exception {
    CheckPointV2Store store = new CheckPointV2Store("test-close-super");
    
    // Get the parent class's writeOptions field
    Field parentWriteOptionsField = TronDatabase.class.getDeclaredField("writeOptions");
    parentWriteOptionsField.setAccessible(true);
    WriteOptionsWrapper originalParentWriteOptions = (WriteOptionsWrapper) parentWriteOptionsField.get(store);
    
    // Save the original rocks object reference for subsequent verification
    org.rocksdb.WriteOptions originalRocks = originalParentWriteOptions.rocks;
    
    // Create a spy to monitor the parent class's writeOptions
    WriteOptionsWrapper spyParentWriteOptions = spy(originalParentWriteOptions);
    parentWriteOptionsField.set(store, spyParentWriteOptions);
    
    // Create a spy to monitor the rocks.close() method
    org.rocksdb.WriteOptions spyRocks = spy(originalRocks);
    spyParentWriteOptions.rocks = spyRocks;
    
    // Verify that the parent class's writeOptions and dbSource exist
    Assert.assertNotNull(spyParentWriteOptions);
    Assert.assertNotNull(spyRocks);
    Assert.assertNotNull(store.getDbSource());
    
    // Close the store
    store.close();
    
    // Verify that the parent class's writeOptions.close() was called (via super.close())
    verify(spyParentWriteOptions, times(1)).close();
    
    // Verify that rocks.close() was called (resources are actually closed)
    verify(spyRocks, times(1)).close();
  }
}
