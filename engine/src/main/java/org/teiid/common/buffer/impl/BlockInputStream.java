/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.common.buffer.impl;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * TODO: support freeing of datablocks as we go
 */
final class BlockInputStream extends InputStream {
	private final BlockManager manager;
	private final int maxBlock;
	int blockIndex;
	ByteBuffer buf;
	boolean done;
	private final boolean threadSafe;

	BlockInputStream(BlockManager manager, int blockCount, boolean threadSafe) {
		this.manager = manager;
		this.maxBlock = blockCount;
		this.threadSafe = threadSafe;
	}

	@Override
	public int read() {
		ensureBytes();
		if (done) {
			return -1;
		}
		return buf.get() & 0xff;
	}

	private void ensureBytes() {
		if (buf == null || buf.remaining() == 0) {
			if (maxBlock == blockIndex) {
				done = true;
				return;
			}
			buf = manager.getBlock(blockIndex++);
			if (threadSafe) {
				buf = buf.duplicate();
			}
		}
	}

	@Override
	public int read(byte[] b, int off, int len) {
		ensureBytes();
		if (done) {
			return -1;
		}
		len = Math.min(len, buf.remaining());
		buf.get(b, off, len);
		return len;
	}
	
}