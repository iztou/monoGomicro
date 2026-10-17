# RuoYi 行为保持基线（audit 阶段 0 冻结）

用途：audit 功能实施期间的**行为保持对拍 oracle**——重构 core（PartitionMetrics 重载、
buildResult 统计下沉等）后验证"分区行为逐位不变"。

## 文件

- `ruoyi-analysis-input.json`：冻结的分析输入（analyze 产物）。**analyze 的输出会随 mvn
  本地仓库解析状态漂移**（复核时实测 47 条边权重变化、totalWeight 5901→5984），
  所以对拍必须以冻结的这份为输入，不能现场重新 analyze。
- `ruoyi-partition-before.json`：冻结的分区输出（阶段 2 重构前跑出）。

## 复现与对拍流程

```bash
# 生成时间：2026-10-17 11:50（JDK zulu-17，RuoYi 4.8.3 at ~/Work/gitRepo/RuoYi，--no-maven-classpath）
java -jar m2m.jar analyze ~/Work/gitRepo/RuoYi -p com.ruoyi -o <dir> --no-maven-classpath
java -jar m2m.jar partition -a <dir>/analysis.json -o <dir>

# 对拍（重构后）：用冻结输入跑 partition，diff 输出
java -jar m2m.jar partition -a docs/audit-baseline/ruoyi-analysis-input.json -o /tmp/after
diff docs/audit-baseline/ruoyi-partition-before.json /tmp/after/partition.json
# 期望：无差异（Q=0.7651972625483001，12 服务）
```

## 2026-10-17 基线有意更新（R-11 退火空社区修复）

修复退火目标函数把空社区计入规模均值/方差（project-review R-11）后，同一输入的
partition.json 发生变化：**Q、服务数、crossRatio、每服务规模全部不变，仅社区内
classes 列表的顺序不同**（进退火前 renumber 改变了遍历序）——分组语义完全等价。
此为有意更新，非静默漂移。
