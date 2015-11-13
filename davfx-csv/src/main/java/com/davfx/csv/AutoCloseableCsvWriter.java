package com.davfx.csv;

import java.io.Closeable;

public interface AutoCloseableCsvWriter extends AutoCloseable, Closeable, CsvWriter {
}
