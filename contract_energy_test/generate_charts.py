#!/usr/bin/env python3
"""
根据测试结果生成可视化图表
"""

import json
import matplotlib.pyplot as plt
import matplotlib
import numpy as np
from pathlib import Path

# 设置中文字体支持
matplotlib.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'SimHei', 'DejaVu Sans']
matplotlib.rcParams['axes.unicode_minus'] = False

# 配色方案
COLORS = {
    'primary': '#2E86AB',
    'secondary': '#A23B72',
    'accent': '#F18F01',
    'success': '#C73E1D',
    'light': '#E8F4F8',
    'dark': '#1B4965'
}


def load_data(json_file):
    """加载测试结果数据"""
    with open(json_file, 'r') as f:
        return json.load(f)


def extract_all_functions(data):
    """提取所有函数的详细数据"""
    all_funcs = []
    for contract in data['contracts']:
        if 'functions' in contract:
            for func in contract['functions']:
                if 'energy' in func and func['energy'] is not None:
                    func['contract'] = contract['name']
                    func['contract_addr'] = contract['addr']
                    all_funcs.append(func)
    return all_funcs


def chart_energy_distribution(data, save_path):
    """
    图表1: Energy 消耗分布直方图
    展示所有成功测试的函数 Energy 值分布
    """
    all_funcs = extract_all_functions(data)
    all_energies = [f['energy'] for f in all_funcs]
    
    if not all_energies:
        print("没有 Energy 数据，跳过分布图")
        return
    
    plt.figure(figsize=(12, 6))
    
    # 计算合适的 bins
    max_energy = max(all_energies)
    # 动态生成 bins，确保包含所有数据
    if max_energy <= 1000:
        bins = [0, 200, 400, 600, 800, 1000, max(1200, max_energy + 100)]
    elif max_energy <= 5000:
        bins = [0, 500, 1000, 2000, 3000, 4000, 5000, max(6000, max_energy + 500)]
    elif max_energy <= 20000:
        bins = [0, 1000, 2500, 5000, 10000, 15000, 20000, max(25000, max_energy + 1000)]
    else:
        bins = 20  # 让 numpy 自动分箱
    
    n, bins, patches = plt.hist(all_energies, bins=bins, edgecolor='white', 
                                color=COLORS['primary'], alpha=0.7)
    
    # 在每个柱子上方添加数量标签
    for i, (count, x_start, x_end) in enumerate(zip(n, bins[:-1], bins[1:])):
        if count > 0:  # 只显示非零的柱子
            x_center = (x_start + x_end) / 2
            plt.text(x_center, count, f'{int(count)}', 
                    ha='center', va='bottom', fontsize=9, fontweight='bold')
    
    # 设置 X 轴范围（基于实际数据，不要扩展到 1亿）
    max_val = max(all_energies)
    plt.xlim(0, max_val * 1.15)
    
    plt.xlabel('Energy Consumption', fontsize=12)
    plt.ylabel('Number of Function Calls', fontsize=12)
    plt.title('Distribution of Energy Consumption for Constant Calls', fontsize=14, fontweight='bold')
    plt.grid(axis='y', alpha=0.3)
    
    # 添加统计信息 + 安全余量
    stats_text = f'Total Functions: {len(all_energies)}\n'
    stats_text += f'Min: {min(all_energies)} | Max: {max_val}\n'
    stats_text += f'Median: {int(np.median(all_energies))}\n\n'
    stats_text += f'Safety Margin:\n'
    stats_text += f'  10M Limit: {10000000//max_val:,}x\n'
    stats_text += f'  30M Limit: {30000000//max_val:,}x\n'
    stats_text += f'  100M Limit: {100000000//max_val:,}x'
    
    plt.text(0.98, 0.97, stats_text, transform=plt.gca().transAxes,
             verticalalignment='top', horizontalalignment='right',
             bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.8))
    
    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"✓ Saved: {save_path}")


def chart_top_contracts(data, save_path, top_n=20):
    """
    图表2: Top N 合约最大能耗对比
    """
    # 筛选有成功测试数据的合约
    contracts_with_data = []
    for c in data['contracts']:
        if c.get('max') and c.get('max') > 0:
            contracts_with_data.append({
                'name': c['name'][:20],  # 截断长名称
                'max': c['max'],
                'avg': c.get('avg', 0),
                'calls': c.get('calls', 0)
            })
    
    if not contracts_with_data:
        print("没有合约数据，跳过 Top 合约图")
        return
    
    # 按 max energy 排序
    contracts_with_data.sort(key=lambda x: x['max'], reverse=True)
    top_contracts = contracts_with_data[:top_n]
    
    fig, ax = plt.subplots(figsize=(14, 8))
    
    names = [c['name'] for c in top_contracts]
    max_values = [c['max'] for c in top_contracts]
    avg_values = [c['avg'] for c in top_contracts]
    
    x = np.arange(len(names))
    width = 0.35
    
    bars1 = ax.bar(x - width/2, max_values, width, label='Max Energy', 
                   color=COLORS['primary'], alpha=0.8)
    bars2 = ax.bar(x + width/2, avg_values, width, label='Avg Energy',
                   color=COLORS['secondary'], alpha=0.8)
    
    # 在柱子上方添加数值标签
    for bar, val in zip(bars1, max_values):
        height = bar.get_height()
        ax.text(bar.get_x() + bar.get_width()/2., height,
                f'{int(val)}', ha='center', va='bottom', fontsize=7, rotation=90)
    
    # 计算全局最大值用于 Y 轴范围
    global_max = max(max_values)
    ax.set_ylim(0, global_max * 1.2)
    
    ax.set_xlabel('Contracts', fontsize=12)
    ax.set_ylabel('Energy Consumption', fontsize=12)
    ax.set_title(f'Top {top_n} Contracts by Energy Consumption\n'
                 f'(Safety Margin: 10M={10000000//global_max:,}x, 30M={30000000//global_max:,}x)', 
                 fontsize=13, fontweight='bold')
    ax.set_xticks(x)
    ax.set_xticklabels(names, rotation=45, ha='right', fontsize=9)
    ax.legend()
    ax.grid(axis='y', alpha=0.3)
    
    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"✓ Saved: {save_path}")


def chart_safety_margin(data, save_path):
    """
    图表3: 安全余量分析图
    展示当前/建议限制与实际最大能耗的比值
    """
    contracts_with_data = [c for c in data['contracts'] if c.get('max') and c['max'] > 0]
    
    if not contracts_with_data:
        print("没有合约数据，跳过安全余量图")
        return
    
    # 计算各限制下的余量
    limits = {
        '10M': 10_000_000,
        '30M': 30_000_000,
        '100M': 100_000_000
    }
    
    max_energies = [c['max'] for c in contracts_with_data]
    global_max = max(max_energies)
    
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))
    
    # 左图：各限制下的最小余量
    margins = {name: limit / global_max for name, limit in limits.items()}
    
    bars = ax1.bar(margins.keys(), margins.values(), 
                   color=[COLORS['success'], COLORS['accent'], 'red'], alpha=0.7)
    ax1.set_ylabel('Safety Margin (x)', fontsize=12)
    ax1.set_title('Safety Margin by Limit Setting', fontsize=14, fontweight='bold')
    ax1.grid(axis='y', alpha=0.3)
    
    # 在柱子上添加数值
    for bar, margin in zip(bars, margins.values()):
        height = bar.get_height()
        ax1.text(bar.get_x() + bar.get_width()/2., height,
                f'{margin:.0f}x', ha='center', va='bottom', fontsize=11, fontweight='bold')
    
    # 右图：余量分布（以 10M 为例）
    margins_10m = [10_000_000 / c['max'] for c in contracts_with_data]
    margins_10m.sort(reverse=True)
    
    ax2.plot(range(len(margins_10m)), margins_10m, 
             color=COLORS['primary'], linewidth=2, marker='o', markersize=4)
    ax2.axhline(y=1, color='red', linestyle='--', label='Minimum Safe (1x)')
    ax2.fill_between(range(len(margins_10m)), margins_10m, alpha=0.3, color=COLORS['primary'])
    
    ax2.set_xlabel('Contract Rank', fontsize=12)
    ax2.set_ylabel('Safety Margin (10M Limit / Max Energy)', fontsize=12)
    ax2.set_title('Safety Margin Distribution (10M Limit)', fontsize=14, fontweight='bold')
    ax2.legend()
    ax2.grid(alpha=0.3)
    
    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"✓ Saved: {save_path}")


def chart_calls_vs_energy(data, save_path):
    """
    图表4: 调用次数 vs Energy 消耗散点图
    分析高频调用合约是否更耗资源
    """
    # 收集每个函数的详细数据
    funcs_with_calls = []
    for c in data['contracts']:
        if c.get('calls') and 'functions' in c:
            for func in c['functions']:
                if 'energy' in func:
                    funcs_with_calls.append({
                        'calls': c['calls'],
                        'energy': func['energy'],
                        'name': func['name'],
                        'contract': c['name']
                    })
    
    if len(funcs_with_calls) < 10:
        print("数据不足，跳过散点图")
        return
    
    plt.figure(figsize=(12, 8))
    
    calls = [f['calls'] for f in funcs_with_calls]
    energies = [f['energy'] for f in funcs_with_calls]
    
    # 绘制散点
    scatter = plt.scatter(calls, energies, c=energies, cmap='viridis', 
                         s=50, alpha=0.4, edgecolors='none')
    
    # 添加颜色条
    plt.colorbar(scatter, label='Energy')
    
    # 标注前 5 名的高调用合约的函数
    top5_contracts = sorted(set([(f['contract'], f['calls']) for f in funcs_with_calls]), 
                           key=lambda x: x[1], reverse=True)[:5]
    top5_names = [c[0] for c in top5_contracts]
    for f in funcs_with_calls:
        if f['contract'] in top5_names and f['energy'] > np.percentile(energies, 80):
            plt.annotate(f"{f['contract'][:10]}:{f['name'][:8]}", 
                        (f['calls'], f['energy']),
                        xytext=(5, 5), textcoords='offset points',
                        fontsize=7, alpha=0.7)
    
    plt.xscale('log')
    plt.xlabel('Contract Total Calls (log scale)', fontsize=12)
    plt.ylabel('Function Energy Consumption', fontsize=12)
    plt.title('Contract Popularity vs Function Energy Cost', fontsize=14, fontweight='bold')
    plt.grid(True, alpha=0.3)
    
    # 添加相关系数
    correlation = np.corrcoef(np.log10(calls), energies)[0, 1]
    plt.text(0.02, 0.98, f'Correlation: {correlation:.3f}\nTotal Functions: {len(funcs_with_calls)}', 
             transform=plt.gca().transAxes, verticalalignment='top',
             bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
    
    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"✓ Saved: {save_path}")


def main():
    """主函数"""
    json_file = Path("top100_energy_result.json")
    
    if not json_file.exists():
        print(f"错误: 找不到 {json_file}")
        print("请先运行测试脚本生成结果文件:")
        print("  python3 test_energy_final.py")
        return
    
    print("=" * 60)
    print("Contract Energy Test - Chart Generator")
    print("=" * 60)
    
    # 加载数据
    data = load_data(json_file)
    print(f"✓ 加载数据: {len(data.get('contracts', []))} 个合约")
    
    # 生成图表
    output_dir = Path("charts")
    output_dir.mkdir(exist_ok=True)
    
    print("\n生成图表中...")
    
    chart_energy_distribution(data, output_dir / "01_energy_distribution.png")
    chart_top_contracts(data, output_dir / "02_top_contracts.png", top_n=20)
    chart_safety_margin(data, output_dir / "03_safety_margin.png")
    chart_calls_vs_energy(data, output_dir / "04_calls_vs_energy.png")
    
    print("\n" + "=" * 60)
    print(f"所有图表已保存到: {output_dir.absolute()}/")
    print("=" * 60)


if __name__ == "__main__":
    main()
