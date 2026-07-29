package org.example.ai.location;

/**
 * 地理位置 —— 经纬度 + 城市名。
 */
public record GeoLocation(double lat, double lng, String city) {
}