package org.example.ai.location;

/**
 * 门店信息 —— 含经纬度，供 StoreLocator 做距离计算（#10 ticket）。
 */
public record StoreInfo(
        int id,
        String storeName,
        String storeCode,
        String address,
        String phone,
        String workingHours,
        String region,
        double latitude,
        double longitude,
        boolean active
) {}