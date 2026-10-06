package com.queuelab.worker.job;

import java.io.IOException;
import java.io.Reader;

/** Descarta un BOM UTF-8 inicial (U+FEFF); el resto pasa tal cual. */
final class BomSkippingReader extends Reader {

    private final Reader in;
    private boolean first = true;

    BomSkippingReader(Reader in) {
        this.in = in;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int n = in.read(buffer, offset, length);
        if (first && n > 0) {
            first = false;
            if (buffer[offset] == '﻿') {
                System.arraycopy(buffer, offset + 1, buffer, offset, n - 1);
                n--;
                if (n == 0) {
                    return read(buffer, offset, length);
                }
            }
        } else if (first && n < 0) {
            first = false;
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
