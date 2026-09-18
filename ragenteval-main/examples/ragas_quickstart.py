"""RAGAS 快速上手：跑通用户的第一次 LLM-as-judge 评测。

运行前确保：
1. pip install ragas langchain-openai datasets
2. export AIHUBMIX_API_KEY=<your_key>
"""
import os

from datasets import Dataset
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import evaluate
from ragas.metrics._answer_correctness import answer_correctness
from ragas.metrics._answer_relevance import answer_relevancy
from ragas.metrics._context_precision import context_precision
from ragas.metrics._context_recall import context_recall
from ragas.metrics._faithfulness import faithfulness

# ── 1. 配置 judge 模型 ─────────────────────────────────────
api_key = os.environ["AIHUBMIX_API_KEY"]
base_url = os.environ.get("AIHUBMIX_BASE_URL", "https://aihubmix.com/v1")
judge_model = os.environ.get("JUDGE_MODEL", "gpt-5.4-mini")
emb_model = os.environ.get("EMBEDDING_MODEL", "text-embedding-3-large")
answer_relevancy.strictness = int(
    os.environ.get("RAGAS_ANSWER_RELEVANCY_STRICTNESS", "1")
)
show_progress = os.environ.get("RAGAS_SHOW_PROGRESS", "1").lower() in {
    "1",
    "true",
    "yes",
}

judge = ChatOpenAI(
    model=judge_model,
    api_key=api_key,
    base_url=base_url,
    temperature=0,
    timeout=180,
)
emb = OpenAIEmbeddings(
    model=emb_model,
    api_key=api_key,
    base_url=base_url,
    timeout=180,
)

# ── 2. 准备数据（比特严选：AirPods Pro 2 保修期） ──────────
ds = Dataset.from_dict({
    "user_input": [
        "AirPods Pro 2 的保修期是多久？",
        "扫地机器人充不进电怎么办？",
    ],
    "response": [
        "AirPods Pro 2 的标准保修期为 1 年，自购买之日起算，覆盖非人为硬件故障。",
        "建议按以下步骤排查：1) 检查充电座触点是否有灰尘；2) 确认电源线插紧；3) 在 0-40°C 环境静置 30 分钟后重试。如仍无法充电建议联系售后。",
    ],
    "retrieved_contexts": [
        [
            "【AirPods Pro 2 售后政策】标准保修期：自购买之日起 1 年。"
            "保修范围包括非人为损坏的硬件故障。如需延长保修，可购买 AppleCare+ 服务。"
        ],
        [
            "【扫地机器人故障排查】充电异常处理：1) 将主机放回充电座，观察指示灯；"
            "2) 检查电源线是否插紧、插座是否通电；3) 擦拭主机和充电座触点；"
            "4) 在 0-40°C 环境静置 30 分钟后再充电。如反复出现 E13/E14 错误码，"
            "检查附近是否有强磁物体。持续异常建议转人工售后。"
        ],
    ],
    "reference": [
        "AirPods Pro 2 标准保修 1 年，自购买之日起算，覆盖非人为硬件故障。",
        "先按顺序排查：检查充电座触点、确认电源线和插座、擦拭触点、在合适温度环境静置后重试。如有 E13/E14 错误码检查是否靠近强磁物体。持续异常转人工售后。",
    ],
})

# ── 3. 跑评测 ─────────────────────────────────────────────
result = evaluate(
    dataset=ds,
    metrics=[
        faithfulness,
        answer_relevancy,
        answer_correctness,
        context_precision,
        context_recall,
    ],
    llm=judge,
    embeddings=emb,
    show_progress=show_progress,
)

# ── 4. 输出结果 ───────────────────────────────────────────
df = result.to_pandas()
print(
    df[
        [
            "faithfulness",
            "answer_relevancy",
            "answer_correctness",
            "context_precision",
            "context_recall",
        ]
    ]
)
print(f"\n均值：")
for col in ["faithfulness", "answer_relevancy", "answer_correctness", "context_precision", "context_recall"]:
    print(f"  {col:<24s} = {df[col].mean():.3f}")
