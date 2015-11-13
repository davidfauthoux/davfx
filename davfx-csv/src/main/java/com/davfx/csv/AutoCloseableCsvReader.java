package com.davfx.csv;

import java.io.Closeable;

public interface AutoCloseableCsvReader extends AutoCloseable, Closeable, CsvReader {
}
