package com.pedrobatista.fastblake;

public class FastBlake {
    /**
     * Placeholder hash function for project skeleton.
     * Replace with the actual Blake3 implementation.
     */
    public static String hash(String input) {
        if (input == null) return null;
        return Integer.toHexString(input.hashCode());
    }

    public static void main(String[] args) {
        String in = args.length > 0 ? args[0] : "";
        System.out.println(hash(in));
    }
}
