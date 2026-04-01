#!/usr/bin/env python3
"""
测试 TRON 热门合约 Constant Call Energy 消耗 (带 API Key 和延迟)
数据来源: Tronscan API (按余额排序的 Top 100 合约)
"""

import os
import time
import json
import requests
from typing import List, Dict, Any, Tuple, Optional

TRONGRID_API = "https://api.trongrid.io"

# 请从环境变量或配置文件读取敏感信息
# 获取方式：
# 1. 设置环境变量：export TRONGRID_API_KEY='your-key'
# 2. 设置环境变量：export OWNER_ADDRESS='your-address'
import os

TRONGRID_API_KEY = os.environ.get("TRONGRID_API_KEY", "")
if not TRONGRID_API_KEY:
    print("错误: 请设置 TRONGRID_API_KEY 环境变量")
    print("export TRONGRID_API_KEY='your-api-key-here'")
    exit(1)

OWNER_ADDRESS = os.environ.get("OWNER_ADDRESS", "")
if not OWNER_ADDRESS:
    print("错误: 请设置 OWNER_ADDRESS 环境变量")
    print("export OWNER_ADDRESS='your-wallet-address'")
    exit(1)

TRONGRID_HEADERS = {
    "Accept": "application/json",
    "Content-Type": "application/json",
    "User-Agent": "Mozilla/5.0",
    "TRON-PRO-API-KEY": TRONGRID_API_KEY
}

# Top 100 合约列表 - 从 Tronscan API 获取 (按 trxCount 调用次数排序)
# API: https://apilist.tronscan.org/api/contracts?sort=-trxCount&limit=100
CONTRACTS = [
    {"addr": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "name": "TetherToken", "calls": 2320782706},
    {"addr": "TNNxU7Jez9Q4bXREBrBxrmTLnzpAtKDRhw", "name": "TrxChainInsurance", "calls": 38473973},
    {"addr": "TVj7RNVHy6thbM7BWdSe9G6gXwKhjhdNZS", "name": "Klever", "calls": 16434732},
    {"addr": "TQn9Y2khEsLJW1ChVWFMSMeRDow5KcbLSE", "name": "CreatedByContract", "calls": 10317010},
    {"addr": "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7", "name": "WINKLINK", "calls": 9289019},
    {"addr": "THnSDgi6Do7Kvqhys7PndZvPVGzGRN4Y7c", "name": "TrxChain", "calls": 8620423},
    {"addr": "TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB", "name": "PumpswapRouter", "calls": 8380780},
    {"addr": "TFczxzPhnThNSqr5by8tvxsdCFRRz6cPNq", "name": "APENFT", "calls": 6566861},
    {"addr": "TLFupxZS4m66Vfc1gcFH5pfiAFhmXsc7Kg", "name": "TronDivs", "calls": 5447071},
    {"addr": "TCFLL5dx5ZJdKnWuesXxi1VPwjLVmWZZy9", "name": "JST", "calls": 4572230},
    {"addr": "TAFjULxiVgT4qWk6UZwjqwZXTSaGaqnVp4", "name": "BTT", "calls": 3786177},
    {"addr": "TVRqMwBZU13c4wx6Hi6jqroCnYv2nrqQU1", "name": "TGOLDe", "calls": 3712191},
    {"addr": "TTfvyrAz86hbZk5iDpKD78pqLGgi8C7AAw", "name": "LaunchPadProxy", "calls": 3184918},
    {"addr": "TKkeiboTkxXKJpbmVFbv4a8ov5rAfRDMf9", "name": "SunToken", "calls": 2661136},
    {"addr": "TQYuR8FpmhMJK3ZpfoTgERjaZRywqMnFAH", "name": "PAYNET", "calls": 2640174},
    {"addr": "TWiti6GpPJPRXtCFKURdTtxN7UEcurQoeS", "name": "TRONex", "calls": 2562054},
    {"addr": "TRzwSBRFzfUuKwTAh7Yh4ih6UGTfaDDrGY", "name": "TRONtopia_classic_dice", "calls": 1989458},
    {"addr": "TU2MJ5Veik1LRAgjeSzEdvmDYx7mefJZvd", "name": "MarketProxy", "calls": 1767388},
    {"addr": "TREbha3Jj6TrpT7e6Z5ukh3NRhyxHsmMug", "name": "FORSAGE_TRX_COMMUNITY", "calls": 1697041},
    {"addr": "TLkyqyLBhLqYC3tvMRb41RmcudGSbxXYUH", "name": "BeeHive", "calls": 1527849},
    {"addr": "TUKxxRkFi21d6KnqUi7aNsbA2So91xMDFG", "name": "DEGREECRYPTO", "calls": 1412892},
    {"addr": "THPMGRqM7asytcp7nMu6MBR8X3dV3NiLa8", "name": "Router", "calls": 1398387},
    {"addr": "TUEYcyPAqc4hTg1fSuBCPc18vGWcJDECVw", "name": "CreatedByContract", "calls": 1379858},
    {"addr": "THzHLVopV7DF24fWThBjeVxr1x1FYBSwSN", "name": "TronHivesFast", "calls": 1378638},
    {"addr": "TFVisXFaijZfeyeSjCEVkHfex7HGdTxzF9", "name": "SmartExchangeRouter", "calls": 1290336},
    {"addr": "TYukBQZ2XXCcRCReAUguyXncCWNY9CEiDQ", "name": "CreatedByContract", "calls": 1246163},
    {"addr": "TKcKSBdc5q4sAAh2EBhJQaHBwMToG1cmLo", "name": "TronExpress", "calls": 1187552},
    {"addr": "TDKmefRcHcSUj4RoGkaUy2AxJzyqG1xr1z", "name": "MOMOToken", "calls": 1155271},
    {"addr": "TYkQyq6Bbw3ZoZYVmKCuePexoNkoLDEf7i", "name": "NB_Mining", "calls": 1136576},
    {"addr": "TRtSQfrfes9Q4eYLaa5uqtmYHG7mL4pM7f", "name": "TrxDoubler", "calls": 1130598},
    {"addr": "TKfjV9RNKJJCqPvBtK8L7Knykh7DNWvnYt", "name": "WBTT", "calls": 1089443},
    {"addr": "TGrc7Je5emcrqcuNfjKBoRgGh36WDzd1xL", "name": "TRONtopia_classic_dice", "calls": 1082924},
    {"addr": "TTn3SwRfohHJrE4dEB5NsUL2nGxTYGC8aW", "name": "Token", "calls": 1050470},
    {"addr": "TJxiQuTpzf9LoLRJ6mmPDb9DV5vKDjW6m7", "name": "MMMDeFi", "calls": 1029864},
    {"addr": "TMM5JNAbdhberXqrCFnDa8hCtfcz1GHtTG", "name": "TronHero", "calls": 981310},
    {"addr": "TDk91SWz2GvwfZwMTGX21d4ngUUH8YZZAv", "name": "OSKToken", "calls": 970112},
    {"addr": "TSSMHYeV2uE9qYH95DqyoCuNCzEL1NvU3S", "name": "SunToken", "calls": 953076},
    {"addr": "TF8iBW9y1KL9pXQwbNmZ6FjBs2TP2pMszf", "name": "Metavarious", "calls": 928777},
    {"addr": "TKDf59Mip1MvmDtLN4fUeLNukpb1pcg3mW", "name": "TronHero", "calls": 899795},
    {"addr": "TJ4NNy8xZEqsowCBhLvZ45LCqPdGjkET5j", "name": "SmartExchangeRouter", "calls": 899434},
    {"addr": "TXF1xDbVGdxFGbovmmmXvBGu8ZiE3Lq4mR", "name": "SunswapV2Router02", "calls": 867860},
    {"addr": "TWfoPHVjZrbbjnqtHWYQFk6CJ44JqPqngb", "name": "Token", "calls": 840770},
    {"addr": "TXPSDDFZjvEsNyCswjv59mvo6NxnAX3e2X", "name": "TRON2Get", "calls": 773607},
    {"addr": "TRGxi9hLeNZgponDsHgLvLwDQoq7U5VPTF", "name": "BankrollFlow", "calls": 764292},
    {"addr": "TPwezUWpEGmFBENNWJHwXHRG1D2NCEEt5s", "name": "Bridgers", "calls": 756137},
    {"addr": "TZ7trrn98aT26UGTLUSieq2GRfwjfmn7Lq", "name": "StakingDCT", "calls": 720645},
    {"addr": "TR58U6HW3BFaFCPRojEtHp5AUbcTWtoibW", "name": "YASION", "calls": 695296},
    {"addr": "TXL6rJbvmjD46zeN1JssfgxvSo99qC8MRT", "name": "CreatedByContract", "calls": 682432},
    {"addr": "TSsyjUxFkcnZ8p6HihCvFfry2xtvcxcpfu", "name": "TronProfits", "calls": 669958},
    {"addr": "TSMePsQvdDw1eyVHDbXfu9gqrGh4hfe1JM", "name": "TRC20Proxy", "calls": 664991},
    {"addr": "TJ8TutTbUvvhM27Ka8vAxzAKGgTLUaSvm8", "name": "PGNLZStake", "calls": 652811},
    {"addr": "TYrHJbBbjXo8F1r1JqqgcFzAzC9NCKYf2r", "name": "Token", "calls": 637468},
    {"addr": "TDQaYrhQynYV9aXTYj63nwLAafRffWSEj6", "name": "CreatedByContract", "calls": 629019},
    {"addr": "TEorZTZ5MHx8SrvsYs1R3Ds5WvY1pVoMSA", "name": "SwftSwap", "calls": 626686},
    {"addr": "TAP7qf8Ao26ZAKYS5E6SGozUNoSLvBHsGa", "name": "YFX", "calls": 626163},
    {"addr": "TM4ca1uM7undib12H26eHfD4FhAGknVzCX", "name": "TronDrop", "calls": 616896},
    {"addr": "TUBA2HhtN7xxEZ8mYwAk26kNEfwKVcSJNS", "name": "BankrollFlow", "calls": 607362},
    {"addr": "TRwptGFfX3fuffAMbWDDLJZAZFmP6bGfqL", "name": "DegreeCryptoToken", "calls": 603857},
    {"addr": "TM1c3mxFz5Y6oQnuuA7NZbonRa4X7Dchgz", "name": "Token", "calls": 597193},
    {"addr": "TVZDwu65xaL31HyP9w19KEtT87XFaM4kqt", "name": "TronRoyal", "calls": 590944},
    {"addr": "TF9XjjnXAkpwXEc9WmmNWNgvDpBK9kJsxz", "name": "Token", "calls": 580361},
    {"addr": "TGTE9Eo4hrkotStM1iGHSKA7gobxZxPuwQ", "name": "BreedTech", "calls": 578844},
    {"addr": "TF3N6yDLfhosmx4LEqiq7pLcUWxdYMMfWV", "name": "ECN", "calls": 568730},
    {"addr": "TThzxNRLrW2Brp9DcTQU8i4Wd9udCWEdZ3", "name": "StUSDTProxy", "calls": 563734},
    {"addr": "TLQJuGoiDjDnNT4Yjd6rAU9CZB6PFnjzvR", "name": "Token", "calls": 546635},
    {"addr": "THsSSczBw9RRMJWYL5j2MtcgaPasL2xPGP", "name": "T2X", "calls": 538993},
    {"addr": "TLX771cbbQTWKdKpkg6QvX51P5oNFBA11x", "name": "Troncase", "calls": 531567},
    {"addr": "TXMKm8FSp6zcm3cdgkgcM1fwdkuJbZ4Hgv", "name": "WOK", "calls": 529274},
    {"addr": "TXHv4N6bbUnEogb7TJmsrWf8GVePtgdo4B", "name": "CAN", "calls": 519297},
    {"addr": "TPYmHEhy5n8TCEfYGqW2rPxsghSfzghPDn", "name": "USDD", "calls": 512484},
    {"addr": "TKk6DLX1xWRKHjDhHfdyQKefnP1WUppEXB", "name": "DVK", "calls": 509961},
    {"addr": "TSQo8Nn7muPZ78CAjhGCobUnd3m6vLKnNw", "name": "BSG", "calls": 502782},
    {"addr": "TMWtx3BHgNgHtQCJG1WyPoFEu6m9W7VRA5", "name": "BeeHive", "calls": 487876},
    {"addr": "TF9WEfXmyVjkLmosWW9Aave8ZgDFUfN23z", "name": "NB_Token", "calls": 481966},
    {"addr": "TE2RzoSV3wFK99w6J9UnnZ4vLfXYoxvRwP", "name": "JustLend TRX", "calls": 477434},
    {"addr": "TRLDKYXuSqGEkHowm3DvAja8rQvt8dbu4d", "name": "BankOfTron", "calls": 466852},
    {"addr": "TXhdP2yNcUTRrYGyVgNkgqqFjL1sQvCVb1", "name": "LionShare", "calls": 453547},
    {"addr": "TAQuJmiy83mcnyAtB6wMST6bSYamyiHA67", "name": "WaterBridgeToken", "calls": 452601},
    {"addr": "TJoSXFhctyq8Ug2rD8WLKDsDGAWUXf5rDm", "name": "Token", "calls": 447327},
    {"addr": "TUpMhErZL2fhh4sVNULAbNKLokS4GjC1F4", "name": "TrueUSD", "calls": 440258},
    {"addr": "TSiiYf1b1PV1fpT9T7V4wy11btsNVajw1g", "name": "PumpSmartExchangeRouter", "calls": 438583},
    {"addr": "TVGSCwYL79FcVS3uQXfXSzHLGtyva8PLxS", "name": "MMMDapp", "calls": 436234},
    {"addr": "TJvqNiWUN2v2NBG12UhfV7WSvReJkRP3VC", "name": "BridgeToken", "calls": 428397},
    {"addr": "TPteDwgC28Df5XYeXvTrR2xyszbxengjFx", "name": "Tronadz", "calls": 412218},
    {"addr": "TKSLNVrDjb7xCiAySZvjXB9SxxVFieZA7C", "name": "BNKRTokenX", "calls": 406420},
    {"addr": "TLddhpugmCMTHg25vQKZJU8iwkbVrhm32X", "name": "YASION", "calls": 402832},
    {"addr": "TFrBVjdpsuWQUMtjFpMxhUKg2q3oa6rgGv", "name": "TronFlowTRX", "calls": 385321},
    {"addr": "TF8MV9ogKfwK7xxxNF2HEWtE44gEFj9K8T", "name": "Token", "calls": 372928},
    {"addr": "TJZkvqaiPtMyrJmevAsAn8mzsH1135CESr", "name": "WaterBridgeToken", "calls": 368212},
    {"addr": "TB4S2pvyX8uQsBPrTDWYCuSDfYSg6tMJm7", "name": "SwapX", "calls": 367577},
    {"addr": "TNo59Khpq46FGf4sD7XSWYFNfYfbc8CqNK", "name": "HyperToken", "calls": 359550},
    {"addr": "THqRrG8wFawSgMGrw8u2Kua8KWgw186Jkr", "name": "HXToken", "calls": 359128},
    {"addr": "TVjPNiXRohHupNr44LsDngqQZ5gndan5FT", "name": "TRONtopia_ultimate_dice", "calls": 358833},
    {"addr": "TUd2eEqJ7eGyTwEv5uUARtbPDbj4f3fuxp", "name": "Token", "calls": 356119},
    {"addr": "TMnqP8yuZrKJFXN59KoVDthkmM3LRkiZXP", "name": "PolluxCoin", "calls": 354721},
    {"addr": "TBRWhtShiZvxZoqSU7kzGzGevWcYVzY99C", "name": "TronInBank", "calls": 345060},
    {"addr": "THddki2WEJcyE1sMVsUVzThXgx6poT8cQb", "name": "TRONtopia_classic_dice", "calls": 342696},
    {"addr": "TLrsWQzQ3uNHEGP7gwy6sC36XBsA3M9WZb", "name": "TheMajorityToken", "calls": 340980},
    {"addr": "TXJgMdjVX5dKiQaUi9QobwNxtSQaFqccvd", "name": "CErc20Delegator", "calls": 333979},
    {"addr": "TD74cpU4JfMKhuWVs6uKYXKUy8nvC6EQ95", "name": "Token", "calls": 332801},
]


def api_call(method: str, endpoint: str, payload: Dict = None, retry: int = 2) -> Tuple[Optional[Any], str]:
    """带重试的 API 调用"""
    url = f"{TRONGRID_API}{endpoint}"
    
    for attempt in range(retry):
        try:
            # 延迟控制：有 API Key 时 0.2s，无 Key 时 2s
            time.sleep(0.2)
            
            if method == "GET":
                resp = requests.get(url, headers=TRONGRID_HEADERS, timeout=15)
            else:
                resp = requests.post(url, json=payload, headers=TRONGRID_HEADERS, timeout=15)
            
            if resp.status_code == 429:
                wait = 3 + attempt * 3
                print(f"      Rate limited, waiting {wait}s...")
                time.sleep(wait)
                continue
            
            resp.raise_for_status()
            return resp.json(), "ok"
            
        except Exception as e:
            if attempt < retry - 1:
                time.sleep(3)
                continue
            return None, str(e)[:30]
    
    return None, "max_retries"


def get_abi(addr: str) -> Tuple[Optional[Dict], str]:
    """获取合约 ABI"""
    payload = {"value": addr, "visible": True}
    data, status = api_call("POST", "/wallet/getcontract", payload)
    
    if data is None:
        return None, status
    
    abi = data.get("abi")
    if not abi:
        return None, "no_abi"
    
    return abi, "ok"


def parse_views(abi: Any) -> List[Dict]:
    """解析 view 函数"""
    entrys = abi.get("entrys", []) if isinstance(abi, dict) else abi if isinstance(abi, list) else []
    
    views = []
    for item in entrys:
        if isinstance(item, dict) and item.get("type") == "Function":
            state = item.get("stateMutability", "")
            name = item.get("name", "")
            if state in ["View", "Pure"] and name:
                views.append({
                    "name": name,
                    "inputs": item.get("inputs", []),
                    "outputs": item.get("outputs", [])
                })
    return views


def test_call(addr: str, func: Dict, owner: str) -> Tuple[Optional[int], str]:
    """测试 constant call energy 消耗"""
    func_name = func["name"]
    inputs = func["inputs"]
    
    # 构建 function_selector
    if inputs:
        # 有参数时：funcName(type1,type2,...)
        input_types = [inp.get("type", "") for inp in inputs]
        selector = f"{func_name}({','.join(input_types)})"
        # 编码参数 (简化处理：全填 0)
        param = "0" * (64 * len(inputs))
    else:
        # 无参数时：funcName()
        selector = f"{func_name}()"
        param = ""
    
    payload = {
        "owner_address": owner,
        "contract_address": addr,
        "function_selector": selector,
        "parameter": param,
        "visible": True
    }
    
    data, status = api_call("POST", "/wallet/triggerconstantcontract", payload)
    
    if data is None:
        return None, status
    
    result = data.get("result", {})
    is_success = (result.get("code") == "SUCCESS" or result.get("result") == True)
    
    if not is_success:
        return None, f"fail:{result.get('code', 'unknown')[:10]}"
    
    energy = data.get("energy_used")
    return int(energy) if energy is not None else None, "ok"


def main():
    print("=" * 80)
    print("TRON Top 100 合约 Constant Call Energy 测试")
    print("=" * 80)
    
    print("=" * 80)
    
    results = []
    all_energy = []
    
    # 测试前 100 个合约
    test_count = min(100, len(CONTRACTS))
    
    for idx, c in enumerate(CONTRACTS[:test_count], 1):
        addr = c["addr"]
        name = c["name"]
        calls = c.get("calls", 0)
        
        print(f"[{idx:3d}/{test_count}] {name}")
        print(f"       {addr} | calls: {calls:,}")
        
        # 获取 ABI
        abi, abi_status = get_abi(addr)
        if abi is None:
            print(f"       ❌ ABI: {abi_status}")
            results.append({"name": name, "addr": addr, "error": abi_status})
            continue
        
        views = parse_views(abi)
        print(f"       View函数: {len(views)}")
        
        if not views:
            results.append({"name": name, "addr": addr, "tested": 0})
            continue
        
        # 测试最多 5 个函数
        energies = []
        func_results = []
        for func in views[:5]:
            energy, status = test_call(addr, func, OWNER_ADDRESS)
            func_info = {
                "name": func["name"],
                "inputs": [i.get("type", "") for i in func["inputs"]],
                "outputs": [o.get("type", "") for o in func["outputs"]]
            }
            if energy is not None:
                energies.append(energy)
                all_energy.append(energy)
                func_info["energy"] = energy
                func_results.append(func_info)
                print(f"       ✓ {func['name']}: {energy}")
            else:
                func_info["error"] = status
                func_results.append(func_info)
                print(f"       ✗ {func['name']}: {status}")
        
        if energies:
            results.append({
                "name": name,
                "addr": addr,
                "calls": calls,
                "tested": len(energies),
                "min": min(energies),
                "max": max(energies),
                "avg": sum(energies) / len(energies),
                "functions": func_results
            })
    
    # 报告
    print("\n" + "=" * 80)
    print("测试结果")
    print("=" * 80)
    
    success = [r for r in results if r.get("tested", 0) > 0]
    print(f"\n成功测试: {len(success)}/{len(results)} 合约")
    print(f"测试函数: {sum(r['tested'] for r in success)} 个")
    
    if all_energy:
        s = sorted(all_energy)
        print(f"\nEnergy 统计:")
        print(f"  最小: {min(all_energy)}")
        print(f"  最大: {max(all_energy)}")
        print(f"  平均: {sum(all_energy)/len(all_energy):.1f}")
        print(f"  P50:  {s[int(len(s)*0.5)]}")
        if len(s) >= 10:
            print(f"  P90:  {s[int(len(s)*0.9)]}")
        
        print(f"\n各合约最大 Energy:")
        for r in sorted(success, key=lambda x: x["max"], reverse=True)[:15]:
            print(f"  {r['name'][:20]:20} | max={r['max']:>5} | avg={r['avg']:>6.1f}")
    
    # 保存结果到当前文件夹
    with open("top100_energy_result.json", "w") as f:
        json.dump({
            "summary": {
                "total": len(results),
                "success": len(success),
                "functions": sum(r['tested'] for r in success),
                "energy_min": min(all_energy) if all_energy else None,
                "energy_max": max(all_energy) if all_energy else None,
            },
            "contracts": results
        }, f, indent=2)
    
    if all_energy:
        max_e = max(all_energy)
        print(f"\n结论:")
        print(f"  当前限制: 100,000,000")
        print(f"  实测最大: {max_e:,}")
        print(f"  建议 10M: 安全余量 {10_000_000 // max_e}x")


if __name__ == "__main__":
    main()
