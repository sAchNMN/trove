package app.devicesorter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class Naming {
    private Naming() {}

    static String device(String make, String model) {
        if (model == null) return null;
        model = model.replace("\u0000", "").trim();
        if (model.isEmpty() || model.equalsIgnoreCase("unknown")) return null;
        make = make == null ? "" : make.replace("\u0000", "").trim();
        if (make.isEmpty() || model.toLowerCase(Locale.ROOT).startsWith(make.toLowerCase(Locale.ROOT))) {
            return model;
        }
        // Keep Apple's familiar model names instead of inventing a marketing-name mapping.
        return make.equalsIgnoreCase("Apple") ? model : make + " " + model;
    }

    static String folder(String model) {
        String clean = model.replaceAll("[\\x00-\\x1f\\\\/:*?\"<>|]", "_")
                .replaceAll("[. ]+$", "").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "device";
        if (clean.length() > 90) clean = clean.substring(0, 90);
        return clean.equals(model) ? clean : clean + "-" + shortHash(model);
    }

    static String collisionName(String original, int index) {
        if (index == 0) return original;
        int dot = original.lastIndexOf('.');
        return dot > 0
                ? original.substring(0, dot) + " (" + index + ")" + original.substring(dot)
                : original + " (" + index + ")";
    }

    static boolean overlaps(String a, String b) {
        String[] first = a.split(":", 2);
        String[] second = b.split(":", 2);
        if (!first[0].equals(second[0])) return false;
        String firstPath = first[1], secondPath = second[1];
        return firstPath.isEmpty() || secondPath.isEmpty() || firstPath.equals(secondPath)
                || firstPath.startsWith(secondPath + "/") || secondPath.startsWith(firstPath + "/");
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT, "%02x%02x%02x%02x",
                    digest[0] & 255, digest[1] & 255, digest[2] & 255, digest[3] & 255);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
