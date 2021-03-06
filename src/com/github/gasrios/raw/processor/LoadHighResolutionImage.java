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

/*
 * This class makes all transformations deemed too complex to be at org.yoyo.dng.data.ImageFileDirectoryLoader when processing
 * the high resolution image IFD:
 *
 * 1. Reads image strips and converts them to a width X height pixel matrix;
 *
 * 2. Converts camera coordinates to XYZ D50 values;
 *
 * 3. Puts all information spread around several IFDs inside the same data structure.
 *
 * This pretty much ends all the dirty work needed to read the DNG file and makes its information available to people whose
 * business is doing actual photo editing. Just extend this class and consume the info in attributes image and imageData.
 *
 * TODO assuming Orientation = 1
 * TODO assuming Compression = 1
 * TODO assuming PhotometricInterpretation = 34.892
 * TODO assuming PlanarConfiguration = 1
 * TODO assuming SamplesPerPixel = 3. See Tags ReductionMatrix1 and ReductionMatrix2.
 */

package com.github.gasrios.raw.processor;

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import com.github.gasrios.raw.data.Illuminant;
import com.github.gasrios.raw.data.ImageFileDirectory;
import com.github.gasrios.raw.data.Tag;
import com.github.gasrios.raw.lang.DngProcessorException;
import com.github.gasrios.raw.lang.Math;
import com.github.gasrios.raw.lang.RATIONAL;
import com.github.gasrios.raw.lang.SRATIONAL;

public class LoadHighResolutionImage extends AbstractDngProcessor {

	private static final Map<Integer, Illuminant> ILLUMINANTS = new HashMap<Integer, Illuminant>();
	static { for (Illuminant illuminant: Illuminant.values()) ILLUMINANTS.put(illuminant.value, illuminant); }

	protected Map<Object, Object> data;

	protected double[][][] image;

	public LoadHighResolutionImage() { data = new HashMap<Object, Object>(); }

	@Override public void firstIfd(ImageFileDirectory ifd) {
		data.put(Tag.AsShotNeutral, ifd.get(Tag.AsShotNeutral));
		data.put(Tag.CalibrationIlluminant1, ifd.get(Tag.CalibrationIlluminant1));
		data.put(Tag.CalibrationIlluminant2, ifd.get(Tag.CalibrationIlluminant2));
		data.put(Tag.CameraCalibration1, ifd.get(Tag.CameraCalibration1));
		data.put(Tag.ForwardMatrix1, ifd.get(Tag.ForwardMatrix1));
		data.put(Tag.ForwardMatrix2, ifd.get(Tag.ForwardMatrix2));
	}

	@Override public void highResolutionIfd(ImageFileDirectory ifd) throws DngProcessorException {

		data.put(Tag.BitsPerSample, ifd.get(Tag.BitsPerSample));
		data.put(Tag.SamplesPerPixel, ifd.get(Tag.SamplesPerPixel));

		/*
		 * See See Digital Negative Specification Version 1.4.0.0, page 79
		 *
		 * DNG provides for one or two sets of color calibration tags, each set optimized for a different illuminant. If both
		 * sets of color calibration tags are included, then the raw converter should interpolate between the calibrations based
		 * on the white balance selected by the user.
		 *
		 * If two calibrations are included, then it is recommended that one of the calibrations be for a low color temperature
		 * illuminant (e.g., Standard-A) and the second calibration illuminant be for a higher color temperature illuminant
		 * (e.g., D55 or D65). This combination has been found to work well for a wide range of real-world digital camera images.
		 *
		 * DNG versions earlier than 1.2.0.0 allow the raw converter to choose the interpolation algorithm. DNG 1.2.0.0 and
		 * later requires a specific interpolation algorithm: linear interpolation using inverse correlated color temperature.
		 *
		 * To find the interpolation weighting factor between the two tag sets, find the correlated color temperature for the
		 * user-selected white balance and the two calibration illuminants. If the white balance temperature is between two
		 * calibration illuminant temperatures, then invert all the temperatures and use linear interpolation. Otherwise, use
		 * the closest calibration tag set.
		 */

		double[] cameraNeutral = RATIONAL.asDoubleArray((RATIONAL[]) data.get(Tag.AsShotNeutral));

		/*
		 * Calculate the interpolation weighting factor associated with tag AsShotNeutral. Oddly enough, cameraToXYZ_D50 correctly
		 * maps AsShotNeutral to D50 white point for any interpolation weighting factor. This is very good but rather unexpected.
		 * A nice collateral effect is we do not need to iterate over weight to find the right value, just once is enough.
		 * 
		 * For the first step we assume weight = 0.5
		 *
		 * Remember we interpolate over the *inverse* of CCT, so bigger becomes smaller and we need to subtract one from the
		 * interpolation to get the correct weight.
		 */
		double weight =
			1D - Math.normalize(
				1/ILLUMINANTS.get((int) data.get(Tag.CalibrationIlluminant2)).cct,
				1/cct(XYZ2xy(Math.multiply(cameraToXYZ_D50(0.5D, cameraNeutral), cameraNeutral))),
				1/ILLUMINANTS.get((int) data.get(Tag.CalibrationIlluminant1)).cct
			);

		double[][] cameraToXYZ_D50 = cameraToXYZ_D50(weight, cameraNeutral);

		// FIXME TIFF property is LONG, but java arrays have their size defined as int.
		int width   = (int) (long) ifd.get(Tag.ImageWidth);
		int length  = (int) (long) ifd.get(Tag.ImageLength);

		image = new double[width][length][0];

		int pixelSize = 0;
		for (int i = 0; i < ((int) ifd.get(Tag.SamplesPerPixel)); i++) pixelSize += 1 + (((int[]) ifd.get(Tag.BitsPerSample)) [i]-1)/8;

		int rowsPerStrip = (int) (long) ifd.get(Tag.RowsPerStrip);

		double
			minX = Double.MAX_VALUE,
			maxX = Double.MIN_VALUE,
			minY = Double.MAX_VALUE,
			maxY = Double.MIN_VALUE,
			minZ = Double.MAX_VALUE,
			maxZ = Double.MIN_VALUE;

		// See TIFF 6.0 Specification, page 39
		for (int i = 0; i < (int) ((length + rowsPerStrip - 1) / rowsPerStrip); i++) {
			short[] strip;
			strip = ifd.getStripAsShortArray(i);
			for (int j = 0; pixelSize*j < strip.length; j = j + 1) {
				double[] XYZ = com.github.gasrios.raw.lang.Math.multiply(cameraToXYZ_D50, readPixel(strip, j*pixelSize, ifd.getByteOrder()));
				if (minX > XYZ[0]) minX = XYZ[0];
				if (maxX < XYZ[0]) maxX = XYZ[0];
				if (minY > XYZ[1]) minY = XYZ[1];
				if (maxY < XYZ[1]) maxY = XYZ[1];
				if (minZ > XYZ[2]) minZ = XYZ[2];
				if (maxZ < XYZ[2]) maxZ = XYZ[2];
				image[j % width][j/width + i*rowsPerStrip] = XYZ;
			}
		}

		// Fixes rounding errors
		for (int i = 0; i < image.length; i++) for (int j = 0; j < image[0].length; j ++) {
			double[] XYZ = image[i][j];
			XYZ[0] = maxX*Math.normalize(minX, XYZ[0], maxX);
			XYZ[1] = maxY*Math.normalize(minY, XYZ[1], maxY);
			XYZ[2] = maxZ*Math.normalize(minZ, XYZ[2], maxZ);
		}

	}

	// http://www.brucelindbloom.com/index.html?Eqn_XYZ_to_xyY.html
	private double[] XYZ2xy(double[] XYZ) { return new double[] { XYZ[0]/(XYZ[0]+XYZ[1]+XYZ[2]), XYZ[1]/(XYZ[0]+XYZ[1]+XYZ[2]), XYZ[1] }; }

	/*
	 * McCamy's cubic approximation (http://en.wikipedia.org/wiki/Color_temperature#Approximation)
	 *
	 * CCT(x, y) = -449*n^3 + 3525*n^2 - 6823,3*n + 5520,33
	 *
	 * Where
	 *
	 * n = (x - 0,3320)/(y - 0,1858)
	 *
	 * The maximum absolute error for color temperatures ranging from 2856 K (illuminant A) to 6504 K (D65) is under 2 K.
	 */
	private double cct(double[] chromaticityCoordinates) {
		double n = (chromaticityCoordinates[0] - 0.3320D)/(chromaticityCoordinates[1] - 0.1858D);
		return -449D*java.lang.Math.pow(n, 3D) + 3525D*java.lang.Math.pow(n, 2D) - 6823.3D*n + 5520.33D;
	}

	/*
	 * See Digital Negative Specification Version 1.4.0.0, Chapter 6: "Mapping Camera Color Space to CIE XYZ Space"
	 *
	 * CameraToXYZ_D50 = FM * D * Inverse(AB * CC)
	 *
	 * 1. FM: 3-by-n matrix interpolated from the ForwardMatrix1 and ForwardMatrix2 tags.
	 *
	 * 2. D can be computed by finding the neutral for the reference camera:
	 *
	 * D = Invert(AsDiagonalMatrix(ReferenceNeutral))
	 *
	 * ReferenceNeutral = Inverse(AB * CC) * CameraNeutral
	 *
	 * CameraNeutral = AsShotNeutral
	 *
	 * 3. AB: n-by-n matrix, which is zero except for the diagonal entries, which are defined by the AnalogBalance tag.
	 *
	 * For linear DNG files AnalogBalance = [ 1 1 1 ] and hence AB is the identity matrix.
	 *
	 * 4. CC: n-by-n matrix interpolated from the CameraCalibration1 and CameraCalibration2 tags
	 *
	 * If CameraCalibration1 = CameraCalibration2 we can use either and there is no need to interpolate.
	 */
	private double[][] cameraToXYZ_D50(double weight, double[] cameraNeutral) {
		double[][] inverseCC = Math.inverse(Math.vector2Matrix(SRATIONAL.asDoubleArray((SRATIONAL[]) data.get(Tag.CameraCalibration1))));
		return
			Math.multiply(
				Math.multiply(
					Math.weightedAverage(
						Math.vector2Matrix(SRATIONAL.asDoubleArray((SRATIONAL[]) data.get(Tag.ForwardMatrix1))),
						Math.vector2Matrix(SRATIONAL.asDoubleArray((SRATIONAL[]) data.get(Tag.ForwardMatrix2))),
						weight
					),
					Math.inverse(Math.asDiagonalMatrix(com.github.gasrios.raw.lang.Math.multiply(inverseCC, cameraNeutral)))
				),
				inverseCC
			);
	}

	// TODO Assuming in the conversion pixel data is always unsigned. Double check this.
	private double[] readPixel(short[] strip, int offset, ByteOrder byteOrder) {

		// TODO assuming SamplesPerPixel = 3. See Tags ReductionMatrix1 and ReductionMatrix2.
		int samplesPerPixel = ((int) data.get(Tag.SamplesPerPixel));
		int[] bitsPerSample = (int[]) data.get(Tag.BitsPerSample);

		double[] pixel = new double[samplesPerPixel];

		for (int i = 0; i < samplesPerPixel; i++) {
			if (bitsPerSample[i] <= 8) {
				short[] sample = new short[1];
				System.arraycopy(strip, offset, sample, 0, 1);
				pixel[i] = sample[0]/255D;
			} else if (bitsPerSample[i] <= 16) {
				short[] sample = new short[2];
				System.arraycopy(strip, offset, sample, 0, 2);
				pixel[i] = com.github.gasrios.raw.io.DngInputStream.toInt(sample, byteOrder)/65535D;
			} else if (bitsPerSample[i] <= 32) {
				short[] sample = new short[4];
				System.arraycopy(strip, offset, sample, 0, 4);
				pixel[i] = com.github.gasrios.raw.io.DngInputStream.toLong(sample, byteOrder)/4294967295D;
			}
			offset += 1 + (bitsPerSample[i]-1)/8;
		}

		return pixel;

	}

}