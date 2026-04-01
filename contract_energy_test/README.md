# Contract Energy Test 使用说明

## 文件结构

```
contract_energy_test/
├── test_energy_final.py              # 主测试脚本
├── generate_charts.py                # 图表生成脚本
├── top100_energy_result.json         # 测试结果（运行后生成）
├── charts/                           # 图表输出目录（运行后生成）
├── contract_test_output.log          # 历史日志
├── energy_test_report.md             # 测试报告
└── maxEnergyLimitForConstant_调研与优化方案.md  # 调研文档
```

## 使用方法

### 0. 环境配置

**重要**：脚本需要 TronGrid API Key 和钱包地址，请查看 [SETUP.md](SETUP.md) 进行配置。

```bash
# 设置环境变量
export TRONGRID_API_KEY='your-api-key-here'
export OWNER_ADDRESS='your-wallet-address'
```

### 1. 运行测试

测试 Top 100 合约的 constant call energy 消耗：

```bash
python3 test_energy_final.py
```

**配置信息**：
- 测试合约数: 100
- 每个合约最多测试: 5 个 view 函数

**预计运行时间**: 3-5 分钟

**输出文件**:
- `top100_energy_result.json` - 完整测试结果（已添加到 .gitignore，不会被提交）

### 2. 生成图表

根据测试结果生成可视化图表：

```bash
python3 generate_charts.py
```

**生成图表**（保存在 `charts/` 目录）：

| 图表 | 文件名 | 说明 |
|------|--------|------|
| Energy 分布直方图 | `01_energy_distribution.png` | 所有函数调用 Energy 值分布 |
| Top 20 合约对比 | `02_top_contracts.png` | 能耗最高的 20 个合约对比 |
| 安全余量分析 | `03_safety_margin.png` | 10M/30M/100M 限制的安全余量 |
| 调用次数 vs Energy | `04_calls_vs_energy.png` | 高频合约是否更耗资源 |

### 3. 查看结果

```bash
# 查看 JSON 结果
cat top100_energy_result.json | python3 -m json.tool

# 查看图表
open charts/01_energy_distribution.png
```

## 关键指标说明

### Energy 统计
- **Min**: 最小 Energy 消耗
- **Max**: 最大 Energy 消耗（关键指标）
- **P50**: 中位数
- **P90**: 90% 分位数

### 安全余量计算
```
安全余量 = 限制值 / 实测最大值

例如:
- 实测最大: 1,017
- 10M 限制: 10,000,000 / 1,017 = 9,832x 余量
```

## 数据来源

合约列表来自 Tronscan API（按调用次数排序）：
```
GET https://apilist.tronscan.org/api/contracts?sort=-trxCount&limit=100
```

Top 3 合约：
1. TetherToken (USDT) - 23.2亿次调用
2. TrxChainInsurance - 3847万次调用
3. Klever - 1643万次调用

## 注意事项

1. **API Key 安全**: 使用环境变量存储，不会出现在代码中（详见 SETUP.md）
2. **不要提交结果文件**: `top100_energy_result.json` 和图表已添加到 `.gitignore`
3. **限流**: 即使有 API Key，仍建议不要频繁运行
4. **结果解释**: `OTHER_ERROR` 通常是合约内部错误，不影响测试有效性

## 调研结论

基于前期测试数据（前 10 个合约）：

| 统计项 | Energy |
|--------|--------|
| Min | 266 |
| P50 | 424 |
| P90 | 858 |
| **Max** | **1,017** |

**建议**:
- 当前限制: 100M
- 建议调整为: **10M**（保守）或 **30M**（平衡）
- 安全余量: 10,000x 以上
