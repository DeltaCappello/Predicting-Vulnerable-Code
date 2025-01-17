/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.websocket;

import java.io.IOException;

import org.apache.coyote.http11.upgrade.UpgradeProcessor;
import org.apache.tomcat.util.res.StringManager;

/**
 * This class is used to read WebSocket frames from the underlying socket and
 * makes the payload available for reading as an {@link InputStream}. It only
 * makes the number of bytes declared in the payload length available for
 * reading even if more bytes are available from the socket.
 */
public class WsInputStream extends java.io.InputStream {

    private static final StringManager sm =
            StringManager.getManager(Constants.Package);


    private UpgradeProcessor<?> processor;
    private WsOutbound outbound;

    private WsFrame frame;
    private long remaining;
    private long readThisFragment;

    private String error = null;


    public WsInputStream(UpgradeProcessor<?> processor, WsOutbound outbound)
            throws IOException {
        this.processor = processor;
        this.outbound = outbound;
        processFrame();
    }


    public WsFrame getFrame() {
        return frame;
    }


    private void processFrame() throws IOException {
        frame = new WsFrame(processor);
        readThisFragment = 0;
        remaining = frame.getPayLoadLength();
    }


    // ----------------------------------------------------- InputStream methods

    @Override
    public int read() throws IOException {

        makePayloadDataAvailable();

        if (remaining == 0) {
            return -1;
        }

        remaining--;
        readThisFragment++;

        int masked = processor.read();
        if(masked == -1) {
            return -1;
        }
        return masked ^
                (frame.getMask()[(int) ((readThisFragment - 1) % 4)] & 0xFF);
    }


    @Override
    public int read(byte b[], int off, int len) throws IOException {

        makePayloadDataAvailable();

        if (remaining == 0) {
            return -1;
        }

        if (len > remaining) {
            len = (int) remaining;
        }
        int result = processor.read(b, off, len);
        if(result == -1) {
            return -1;
        }

        for (int i = off; i < off + result; i++) {
            b[i] = (byte) (b[i] ^
                    frame.getMask()[(int) ((readThisFragment + i - off) % 4)]);
        }
        remaining -= result;
        readThisFragment += result;
        return result;
    }


    /*
     * Ensures that there is payload data ready to read.
     */
    private void makePayloadDataAvailable() throws IOException {
        if (error != null) {
            throw new IOException(error);
        }
        while (remaining == 0 && !getFrame().getFin()) {
            // Need more data - process next frame
            processFrame();
            while (frame.isControl()) {
                if (getFrame().getOpCode() == Constants.OPCODE_PING) {
                    outbound.pong(frame.getPayLoad());
                } else if (getFrame().getOpCode() == Constants.OPCODE_PONG) {
                    // NO-OP. Swallow it.
                } else if (getFrame().getOpCode() == Constants.OPCODE_CLOSE) {
                    outbound.close(frame);
                } else{
                    throw new IOException(sm.getString("is.unknownOpCode",
                            Byte.valueOf(getFrame().getOpCode())));
                }
                processFrame();
            }
            if (getFrame().getOpCode() != Constants.OPCODE_CONTINUATION) {
                error = sm.getString("is.notContinutation",
                        Byte.valueOf(getFrame().getOpCode()));
                throw new IOException(error);
            }
        }
    }
}
