/*
 * © 2016 Guilherme Rios All Rights Reserved
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.  If not, see http://www.gnu.org/licenses/.
 */

// TODO Add default values to all fields that have them.
package com.github.gasrios.raw.data;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.List;
import java.util.TreeMap;

import com.github.gasrios.raw.io.DngInputStream;
import com.github.gasrios.raw.lang.DngProcessorException;
import com.github.gasrios.raw.lang.DngProcessorRuntimeException;

public final class ImageFileDirectory extends TreeMap<Tag, Object> {

	private static final long serialVersionUID = 5585061551958235373L;

	private DngInputStream in;

	public ImageFileDirectory(DngInputStream in) { this.in = in; }

	// See org.yoyo.dng.processor.LoadHighResolutionImage.processHighResolutionIfdEnd(ImageFileDirectory)
	public ByteOrder getByteOrder() { return in.getByteOrder(); }

	// TODO Unused so far, but needed when JPEG compression is used.
	public short[] getTileAsShortArray(int tileNumber) throws DngProcessorException {
		return getImagePartAsShortArray(tileNumber, Tag.TileOffsets, Tag.TileByteCounts);
	}

	// See org.yoyo.dng.processor.LoadHighResolutionImage.processHighResolutionIfdEnd(ImageFileDirectory)
	public short[] getStripAsShortArray(int stripNumber) throws DngProcessorException {
		return getImagePartAsShortArray(stripNumber, Tag.StripOffsets, Tag.StripByteCounts);
	}

	// TODO Validations (is image stored in this tag? Is number valid? Does this IFD has an image at all?)
	private short[] getImagePartAsShortArray(int number, Tag offsets, Tag byteCounts) throws DngProcessorException {

		try {

			long offset;
			long byteCount;

			if (get(offsets) instanceof List) {
				offset = ((long[]) get(offsets))[number];
				byteCount = ((long[]) get(byteCounts))[number];
			} else {
				offset = (long) get(offsets);
				byteCount = (long) get(byteCounts);
			}

			if (byteCount > 0xFFFFFFFFL)
				throw new DngProcessorException("java arrays do not support lengths out of the positive integer range: " + byteCount);

			short[] array = new short[(int) byteCount];
			in.seek(offset);
			in.read(array);
			return array;

		} catch (IOException e) {

			throw new DngProcessorRuntimeException(e);

		}

	}

}