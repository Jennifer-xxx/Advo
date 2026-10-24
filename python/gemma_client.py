"""Minimal client for any local OpenAI-compatible server (litert-lm serve, llama-server, Ollama).

Inference still runs on the local machine; this only talks to localhost. Standard library only.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

DEFAULT_BASE_URL = os.environ.get("GEMMA_BASE_URL", "http://localhost:8080/v1")
DEFAULT_MODEL = os.environ.get("GEMMA_MODEL", "gemma")


@dataclass
class Completion:
    text: str
    seconds: float
    finish: str = ""          # "stop" = finished normally, "length" = ran out of max_tokens
    reasoning_chars: int = 0  # >0 means the server let the model "think" first (we turn that off)


class GemmaClient:
    def __init__(self, base_url: str = DEFAULT_BASE_URL, model: str = DEFAULT_MODEL,
                 temperature: float = 0.0, timeout: float = 180.0):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.temperature = temperature
        self.timeout = timeout
        self.template_kwargs = True  # turned off automatically if the server rejects the field

    def complete(self, prompt: str, max_tokens: int = 512) -> Completion:
        payload = {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": self.temperature,
            "max_tokens": max_tokens,
            "stream": False,
            # Gemma 4 can "think" before answering; on llama.cpp that eats the token budget and the
            # JSON never arrives. The phone app does not think, so turn it off here too.
            "chat_template_kwargs": {"enable_thinking": False},
        }
        if not self.template_kwargs:
            payload.pop("chat_template_kwargs")
        req = urllib.request.Request(
            f"{self.base_url}/chat/completions", data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": "Bearer local"},
        )
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as err:
            detail = err.read().decode("utf-8", "replace")[:500]
            if err.code in (400, 422) and self.template_kwargs:
                self.template_kwargs = False
                return self.complete(prompt, max_tokens)
            raise RuntimeError(f"server returned HTTP {err.code}: {detail}") from None
        seconds = time.perf_counter() - t0
        choice = payload["choices"][0]
        msg = choice.get("message", {})
        text = msg.get("content") or ""
        return Completion(text=text.strip(), seconds=seconds, finish=choice.get("finish_reason") or "",
                          reasoning_chars=len(msg.get("reasoning_content") or ""))


class FunctionClient:
    """Test double: wraps a function prompt -> text so the pipeline runs without a model."""

    def __init__(self, fn):
        self.fn = fn
        self.prompts: list[str] = []

    def complete(self, prompt: str, max_tokens: int = 512) -> Completion:
        self.prompts.append(prompt)
        t0 = time.perf_counter()
        text = self.fn(prompt)
        return Completion(text=text, seconds=time.perf_counter() - t0)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="Smoke test: send one prompt to the local server.")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("prompt", nargs="?", default="用一句话介绍你自己。")
    args = ap.parse_args()
    out = GemmaClient(args.base_url, args.model).complete(args.prompt)
    print(out.text)
    print(f"\n[{out.seconds:.2f}s finish={out.finish} thinking_chars={out.reasoning_chars}]")
    if out.reasoning_chars:
        print("!! The server is still letting the model think. Restart llama-server with --reasoning-budget 0")
