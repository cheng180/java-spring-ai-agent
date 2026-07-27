package org.example.ai.model;

/**
 * 车辆库存数据模型
 * 对应数据库 inventory 表
 */
public class CarInventory {

    private Long id;
    private String stockCode;
    private String brand;
    private String model;
    private String variant;
    private Integer year;
    private Double priceGuide;
    private Double priceSale;
    private Integer stock;
    private Integer monthlySales;
    private Double popularity;
    private String fuelType;
    private String color;
    private String category;
    private String description;

    // ===== 用于排序和展示的辅助字段 =====
    private Double finalScore;       // 综合排序得分
    private Double semanticScore;    // 语义相似度得分
    private Double hotScore;         // 热度得分

    public CarInventory() {}

    // ===== getters =====
    public Long getId() { return id; }
    public String getStockCode() { return stockCode; }
    public String getBrand() { return brand; }
    public String getModel() { return model; }
    public String getVariant() { return variant; }
    public Integer getYear() { return year; }
    public Double getPriceGuide() { return priceGuide; }
    public Double getPriceSale() { return priceSale; }
    public Integer getStock() { return stock; }
    public Integer getMonthlySales() { return monthlySales; }
    public Double getPopularity() { return popularity; }
    public String getFuelType() { return fuelType; }
    public String getColor() { return color; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public Double getFinalScore() { return finalScore; }
    public Double getSemanticScore() { return semanticScore; }
    public Double getHotScore() { return hotScore; }

    // ===== setters =====
    public void setId(Long id) { this.id = id; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public void setBrand(String brand) { this.brand = brand; }
    public void setModel(String model) { this.model = model; }
    public void setVariant(String variant) { this.variant = variant; }
    public void setYear(Integer year) { this.year = year; }
    public void setPriceGuide(Double priceGuide) { this.priceGuide = priceGuide; }
    public void setPriceSale(Double priceSale) { this.priceSale = priceSale; }
    public void setStock(Integer stock) { this.stock = stock; }
    public void setMonthlySales(Integer monthlySales) { this.monthlySales = monthlySales; }
    public void setPopularity(Double popularity) { this.popularity = popularity; }
    public void setFuelType(String fuelType) { this.fuelType = fuelType; }
    public void setColor(String color) { this.color = color; }
    public void setCategory(String category) { this.category = category; }
    public void setDescription(String description) { this.description = description; }
    public void setFinalScore(Double finalScore) { this.finalScore = finalScore; }
    public void setSemanticScore(Double semanticScore) { this.semanticScore = semanticScore; }
    public void setHotScore(Double hotScore) { this.hotScore = hotScore; }

    /** 车款唯一标识（品牌+车型+配置） */
    public String getCarKey() {
        return (brand != null ? brand : "") + " " +
               (model != null ? model : "") + " " +
               (variant != null ? variant : "");
    }

    /** 格式化价格 */
    public String getPriceDisplay() {
        if (priceSale != null && priceGuide != null) {
            double discount = priceGuide - priceSale;
            return String.format("%.2f万（指导价%.2f万，优惠%.2f万）", priceSale, priceGuide, discount);
        } else if (priceSale != null) {
            return String.format("%.2f万", priceSale);
        }
        return "价格待询";
    }

    /** 格式化库存状态 */
    public String getStockDisplay() {
        if (stock == null || stock <= 0) return "暂无库存";
        if (stock <= 2) return String.format("仅剩%d台", stock);
        return String.format("库存%d台", stock);
    }
}
