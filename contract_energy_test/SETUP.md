# 环境配置说明

## 前提条件

1. Python 3.8+
2. 安装依赖：`pip3 install requests matplotlib numpy`
3. TronGrid API Key
4. TRON 钱包地址（用于调用 constant call）

## 获取 TronGrid API Key

1. 访问 [TronGrid](https://www.trongrid.io/)
2. 注册账号并登录
3. 创建新的 API Key
4. 复制 Key 用于环境变量配置

## 配置环境变量

### macOS / Linux

```bash
# 临时设置（仅当前终端会话）
export TRONGRID_API_KEY='your-api-key-here'
export OWNER_ADDRESS='your-wallet-address'

# 验证设置
python3 test_energy_final.py
```

### 永久设置（推荐）

添加到 `~/.bashrc` 或 `~/.zshrc`：

```bash
echo 'export TRONGRID_API_KEY="your-api-key"' >> ~/.zshrc
echo 'export OWNER_ADDRESS="your-wallet-address"' >> ~/.zshrc
source ~/.zshrc
```

### Windows (PowerShell)

```powershell
$env:TRONGRID_API_KEY = "your-api-key-here"
$env:OWNER_ADDRESS = "your-wallet-address"
python3 test_energy_final.py
```

## 运行测试

```bash
# 1. 进入目录
cd contract_energy_test

# 2. 运行测试（约 3-5 分钟）
python3 test_energy_final.py

# 3. 生成图表
python3 generate_charts.py
```

## 预期输出

```
charts/
├── 01_energy_distribution.png
├── 02_top_contracts.png
├── 03_safety_margin.png
└── 04_calls_vs_energy.png
```

## 注意事项

1. **API Key 安全**：永远不要将 API Key 提交到 GitHub
2. **速率限制**：免费版 TronGrid 限制 10-20 RPS
3. **测试结果**：`top100_energy_result.json` 已添加到 .gitignore，不会被提交

## 故障排查

### 错误："请设置 TRONGRID_API_KEY 环境变量"

确保环境变量已正确设置：
```bash
echo $TRONGRID_API_KEY  # 应该显示你的 API Key
```

### 错误："请设置 OWNER_ADDRESS 环境变量"

钱包地址必须是有效的 TRON 地址（以 T 开头的 34 位字符串）：
```bash
echo $OWNER_ADDRESS  # 示例：TW64yhbXK6WaGqEUKfv13PWaAxveHMzjxC
```

## 配置文件方式（可选）

如果不想用环境变量，可以创建 `.env` 文件（**不要提交到 Git**）：

```bash
# .env 文件
trongrid_api_key=your-api-key-here
owner_address=your-wallet-address
```

然后在脚本中读取：

```python
from dotenv import load_dotenv
load_dotenv()
```

**注意**：`.env` 已添加到 `.gitignore`，不会被提交。
