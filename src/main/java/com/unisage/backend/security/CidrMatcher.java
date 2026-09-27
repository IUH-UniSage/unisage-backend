package com.unisage.backend.security;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a comma-separated CIDR allowlist (IPv4/IPv6) and matches an address against it.
 * A single malformed block invalidates the whole list, so a typo fails closed too.
 */
public final class CidrMatcher {

    private final List<Block> blocks;

    private CidrMatcher(List<Block> blocks) {
        this.blocks = blocks;
    }

    public static CidrMatcher parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new CidrMatcher(List.of());
        }
        List<Block> parsed = new ArrayList<>();
        for (String part : raw.split(",")) {
            String cidr = part.trim();
            if (cidr.isEmpty()) {
                continue;
            }
            Block block = parseBlock(cidr);
            if (block == null) {
                return new CidrMatcher(List.of());
            }
            parsed.add(block);
        }
        return new CidrMatcher(parsed);
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public boolean matches(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank() || isEmpty()) {
            return false;
        }
        BigInteger addr;
        boolean isV4;
        try {
            InetAddress inet = InetAddress.getByName(stripZoneId(remoteAddr));
            addr = new BigInteger(1, inet.getAddress());
            isV4 = inet.getAddress().length == 4;
        } catch (UnknownHostException e) {
            return false;
        }
        for (Block block : blocks) {
            if (block.isV4 == isV4 && addr.and(block.mask).equals(block.network)) {
                return true;
            }
        }
        return false;
    }

    private static String stripZoneId(String addr) {
        int percent = addr.indexOf('%');
        return percent >= 0 ? addr.substring(0, percent) : addr;
    }

    private static Block parseBlock(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String hostPart = cidr.substring(0, slash);
        String prefixPart = cidr.substring(slash + 1);
        int prefixLen;
        try {
            prefixLen = Integer.parseInt(prefixPart);
        } catch (NumberFormatException e) {
            return null;
        }
        InetAddress inet;
        try {
            inet = InetAddress.getByName(hostPart);
        } catch (UnknownHostException e) {
            return null;
        }
        byte[] bytes = inet.getAddress();
        int totalBits = bytes.length * 8;
        if (prefixLen < 0 || prefixLen > totalBits) {
            return null;
        }
        BigInteger network = new BigInteger(1, bytes);
        BigInteger mask = totalBits == prefixLen
                ? BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE)
                        .xor(BigInteger.ONE.shiftLeft(totalBits - prefixLen).subtract(BigInteger.ONE));
        network = network.and(mask);
        return new Block(network, mask, bytes.length == 4);
    }

    private record Block(BigInteger network, BigInteger mask, boolean isV4) {
    }
}
