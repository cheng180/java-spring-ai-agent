package org.example.ai.location;

/**
 * 门店×车系热度 —— store_car_hot 表的行模型（#11 ticket，决策7）。
 */
public record HotCar(
        String seriesName,
        int saleCount,
        int inquiryCount
) {
    /** 综合热度（销量 + 咨询量） */
    public int totalHeat() {
        return saleCount + inquiryCount;
    }
}