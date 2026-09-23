package com.monogomicro.core.model;

/** 分区参数。 */
public class PartitionOptions {
    /** 静态图权重占比 */
    private double alpha = 0.5;
    /** 动态图权重占比 */
    private double beta = 0.5;
    /** 小于该规模的社区会被并入最相连的邻居社区 */
    private int minCommunitySize = 5;
    /** 目标服务数量上限：超过则把最小社区并入连接最紧的邻居 */
    private int maxServices = 12;
    /** 共享内核：扇入至少横跨的社区数 */
    private int kernelMinCommunities = 3;
    /** 共享内核：最低扇入类数比例（ BaseController 级别的基类/工具约被 8%~10% 的类引用） */
    private double kernelFanInRatio = 0.08;
    private long seed = 42;

    public double getAlpha() { return alpha; }
    public void setAlpha(double alpha) { this.alpha = alpha; }
    public double getBeta() { return beta; }
    public void setBeta(double beta) { this.beta = beta; }
    public int getMinCommunitySize() { return minCommunitySize; }
    public void setMinCommunitySize(int minCommunitySize) { this.minCommunitySize = minCommunitySize; }
    public int getMaxServices() { return maxServices; }
    public void setMaxServices(int maxServices) { this.maxServices = maxServices; }
    public int getKernelMinCommunities() { return kernelMinCommunities; }
    public void setKernelMinCommunities(int kernelMinCommunities) { this.kernelMinCommunities = kernelMinCommunities; }
    public double getKernelFanInRatio() { return kernelFanInRatio; }
    public void setKernelFanInRatio(double kernelFanInRatio) { this.kernelFanInRatio = kernelFanInRatio; }
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }
}
