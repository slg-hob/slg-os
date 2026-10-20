package de.systemlogistics.slgos.core.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** An IP network range such as {@code 172.20.0.0/16}, used to recognise the proxy network. */
public record Cidr(byte[] network, int prefixLength) {

    /** Parses {@code address/prefix}; only IP literals are accepted (no DNS lookup). */
    public static Cidr parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty range");
        }
        int slash = text.indexOf('/');
        String address = slash < 0 ? text : text.substring(0, slash);
        if (!isIpLiteral(address)) {
            throw new IllegalArgumentException("not an IP literal: " + address);
        }
        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(text.substring(slash + 1));
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("prefix out of range: " + prefix);
            }
            return new Cidr(bytes, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid range: " + text, e);
        }
    }

    /** True for a literal address; false for host names, so no DNS lookup can happen. */
    public static boolean isIpLiteral(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean v4 = text.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = text.indexOf(':') >= 0 && text.matches("[0-9A-Fa-f:.]+");
        return v4 || v6;
    }

    /** True when the address lies in the range; an address of the other IP family never matches. */
    public boolean contains(String address) {
        if (!isIpLiteral(address)) {
            return false;
        }
        try {
            byte[] other = InetAddress.getByName(address).getAddress();
            if (other.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (other[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixLength % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (other[fullBytes] & mask) == (network[fullBytes] & mask);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
