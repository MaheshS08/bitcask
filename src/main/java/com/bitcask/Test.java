package com.bitcask;

import java.util.Arrays;

public class Test  extends AutoCloseable{
    @Override
    public void close() throws Exception {
        int[] arr = new int[5];
        Arrays.fill(arr, -1);
        Arrays.sort();

    }
}
