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

package com.github.gasrios.raw;

import java.io.FileInputStream;

import com.github.gasrios.raw.lang.DngProcessorException;
import com.github.gasrios.raw.processor.DngProcessorEngine;
import com.github.gasrios.raw.processor.LoadHighResolutionImage;
import com.github.gasrios.raw.swing.ImageFrame;
import com.github.gasrios.raw.swing.ImageSRGB;

public class DisplayImage extends LoadHighResolutionImage {

	private static final boolean IGNORE_UNKNOWN_TAGS = true;

	public static void main(String[] args) throws Exception {
		new DngProcessorEngine(new FileInputStream(args[0]), new DisplayImage(), IGNORE_UNKNOWN_TAGS).run();
	}

	@Override public void end() throws DngProcessorException {

		ImageSRGB imageSRGB = new ImageSRGB(image);

		// Does not seem to make much of a difference in practice, but just in case let's try and free some memory here.
		image = null;
		System.gc();

		new ImageFrame(imageSRGB, 1075, 716);

	}

}