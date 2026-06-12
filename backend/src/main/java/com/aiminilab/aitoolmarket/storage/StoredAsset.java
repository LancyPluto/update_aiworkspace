package com.aiminilab.aitoolmarket.storage;

/**
 * Result of persisting a generated or uploaded asset.
 *
 * @param relativeKey path under the media root without {@code /generated/} prefix, e.g. {@code uploads/20260611/abc.jpg}
 * @param publicUrl   URL stored in DB and returned to clients
 * @param storagePath absolute filesystem path (local mode) or {@code oss://bucket/key} (OSS mode)
 */
public record StoredAsset(String relativeKey, String publicUrl, String storagePath) {
}
