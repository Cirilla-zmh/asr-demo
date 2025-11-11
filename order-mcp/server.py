import json
import sys
import uuid

# 最小 MCP/JSON-RPC(模拟)：从 stdin 读入 JSON-RPC 请求，stdout 写回响应
# 方法：order.place {"item": str, "quantity": int}

def handle_request(req):
    method = req.get("method")
    req_id = req.get("id")
    if method == "order.place":
        params = req.get("params", {})
        item = params.get("item", "unknown")
        quantity = int(params.get("quantity", 1))
        order_id = f"ORD-{uuid.uuid4().hex[:8].upper()}"
        result = {"orderId": order_id, "item": item, "quantity": quantity, "status": "CREATED"}
        return {"jsonrpc": "2.0", "id": req_id, "result": result}
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": "Method not found"}}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            resp = handle_request(req)
        except Exception as e:
            resp = {"jsonrpc": "2.0", "id": None, "error": {"code": -32603, "message": str(e)}}
        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()


