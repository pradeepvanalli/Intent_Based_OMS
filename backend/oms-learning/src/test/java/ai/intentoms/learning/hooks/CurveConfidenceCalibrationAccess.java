package ai.intentoms.learning.hooks;

/** Test access to the package-private calibration bin parser. */
public final class CurveConfidenceCalibrationAccess {
    private CurveConfidenceCalibrationAccess() {}

    public static double[] parse(String s) {
        CurveConfidenceCalibration.Bin b = CurveConfidenceCalibration.Bin.parse(s);
        return b == null ? null : new double[]{b.lower(), b.upper(), b.correction()};
    }
}
