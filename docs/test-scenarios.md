# Multi-Region HA — Kịch bản Test theo Timeline

## Architecture

```
                   ┌──────────────┐
                   │  NGINX:8000  │
                   └──────┬───────┘
                          │
          ┌───────────────┴───────────────┐
          │                               │
   ┌──────▼──────┐                ┌───────▼──────┐
   │  APP-US      │                │  APP-EU       │
   │ :8080        │                │ :8081         │
   │ region=us    │                │ region=eu     │
   └──┬─────┬────┘                └──┬──────┬─────┘
      │     │                        │      │
  ┌───▼──┐ ┌▼────┐             ┌────▼──┐ ┌─▼────┐
  │Write │ │Read │             │Write  │ │Read  │
  │Pool  │ │Pool │             │Pool   │ │Pool  │
  └──┬───┘ └┬────┘             └───┬───┘ └┬─────┘
     │      │                      │      │
     │  ┌───▼──────┐               │  ┌───▼──────┐
     │  │postgres  │               │  │postgres  │
     │  │-eu :5433 │               │  │-us :5442 │
     │  │[reader]  │               │  │[writer]  │
     │  └──────────┘               │  └──────────┘
     │                             │
     └────────┬────────────────────┘
              ▼
      ┌──────────────┐
      │ postgres-us  │
      │ :5442        │
      │ [WRITER]     │
      └──────────────┘
```

### Connection Pool

| Pool | Driver | Target | Cơ chế |
|------|--------|--------|--------|
| **WritePool** | AWS JDBC Wrapper (failover2+dev) | Global writer instance | Topology discovery → auto-route tới instance có SESSION_ID='MASTER_SESSION_ID' |
| **ReadPool** | AWS JDBC Wrapper (failover2+dev) | Home region reader | Ưu tiên home region, nếu home region fail → fallback tới region kia |

---

## Test Scenario 1: Baseline (Hoạt động bình thường)

**Mục tiêu:** Xác nhận hệ thống hoạt động đúng với topology mặc định.

| Time | Action | Expected Result |
|------|--------|----------------|
| T+0s | Check health all 5 containers | ✅ All UP |
| T+1s | POST product qua US app (8080) | ✅ Write thành công, id trả về |
| T+1s | POST product qua EU app (8081) | ✅ Write thành công, id trả về |
| T+2s | GET products qua US app | ✅ Trả về 169+ products (home region = us) |
| T+3s | GET products qua EU app | ✅ Trả về 61+ products (home region = eu) |
| T+4s | Check topology: `/admin/topology` | ✅ postgres-us = WRITER, postgres-eu = reader |

**Kết quả:** Cả 2 app write thành công → postgres-us (global writer). ReadPool mỗi app đọc từ home region.

---

## Test Scenario 2: Topology Switch (Chuyển writer sang EU)

**Mục tiêu:** Mô phỏng disaster recovery — chuyển writer từ US sang EU bằng topology update. AWS JDBC Wrapper tự động phát hiện qua `aurora_replica_status()` và route writes tới writer mới.

| Time | Action | Expected Result |
|------|--------|----------------|
| T+0s | k6 traffic bắt đầu (5→20 VUs) | ✅ 100% requests success |
| T+12s | Cập nhật `aurora_replica_status()` trên cả 2 DB: postgres-eu = MASTER_SESSION_ID | ✅ Topology updated |
| T+15s | Wrapper auto-discover topology mới (~5s cache) | ✅ Writes bắt đầu routed tới postgres-eu |
| T+17s | POST product qua US app | ✅ Write vào postgres-eu (new writer) |
| T+18s | POST product qua EU app | ✅ Write vào postgres-eu (new writer) |
| T+20s | Check product tồn tại ở postgres-eu | ✅ Data đã ghi vào EU |
| T+25s | Check product không tồn tại ở postgres-us | ✅ (đúng, writer mới là EU) |
| T+30s | k6 tiếp tục chạy | ✅ Failure rate < 1% (zero-downtime switchover) |

**Kết quả mong đợi:** Zero-downtime switchover. Writes tự động chuyển sang EU nhờ topology discovery.

---

## Test Scenario 3: Kill old writer (postgres-us) sau khi switch

**Mục tiêu:** Sau khi topology đã chuyển writer sang EU, kill postgres-us để simulate region failure.

| Time | Action | Expected Result |
|------|--------|----------------|
| T+0s | k6 traffic chạy ổn định | ✅ |
| T+5s | `docker stop multiregion-us` | ✅ Container dừng |
| T+7s | Check US app health | ⚠️ DB disconnected (readPool mất home) |
| T+8s | Check EU app health | ✅ DB connected (writer = EU, readPool = EU) |
| T+9s | POST product qua EU app | ✅ Writes tới postgres-eu (writer mới) |
| T+10s | k6 tiếp tục | ✅ Writes qua NGINX → app-EU → postgres-eu |
| T+20s | k6 kết thúc | ✅ Failure rate < 5% (chỉ fail requests tới US app) |

**Kết quả mong đợi:** Hệ thống vẫn hoạt động với EU region. US app bị degraded nhưng không ảnh hưởng global writes.

---

## Test Scenario 4: Recovery (Restore original topology)

**Mục tiêu:** Restore postgres-us, trả writer về US.

| Time | Action | Expected Result |
|------|--------|----------------|
| T+0s | `docker start multiregion-us` | ✅ Container start |
| T+5s | Restore topology: postgres-us = MASTER_SESSION_ID | ✅ Topology original |
| T+10s | Check health US app | ✅ DB reconnected, writer = postgres-us |
| T+12s | POST product | ✅ Writes routed tới postgres-us |
| T+15s | GET products qua US app | ✅ ReadPool đọc từ postgres-us (home) |
| T+16s | GET products qua EU app | ✅ ReadPool đọc từ postgres-eu (home) |

**Kết quả:** Full recovery. Hệ thống trở về baseline.

---

## Test Scenario 5: Full Automated k6 Run (Combined)

Kết hợp tất cả các phase trên trong 1 k6 run.

```
k6 Traffic Timeline:
─────────────────────────────────────────────
T+0s  - T+10s:  Warm up (5 VUs)           [Baseline]
T+10s - T+40s:  Normal load (20 VUs)      [Steady state]
T+40s - T+55s:  Switchover + Kill         [Disaster]
T+55s - T+70s:  Recovery verification      [Recovery]
─────────────────────────────────────────────
Failover Events (injected mid-test):

T+12s: Update topology → postgres-eu = MASTER
T+15s: Writes start routing to postgres-eu
T+20s: Kill postgres-us (old writer)
T+25s: Verify writes via postgres-eu
T+30s: Restart postgres-us
T+35s: Restore topology → postgres-us = MASTER
T+40s: Verify full recovery
```

**Metrics thu thập:**
- `http_req_failed` — tỷ lệ request thất bại
- `http_req_duration` — p50, p95, p99 response time
- `failed_requests` — custom rate metric
- `health_response_time` — health endpoint latency
- `product_response_time` — product API latency
- `topology_response_time` — topology endpoint latency

**Kết quả baseline:** < 1% failures = zero-downtime switchover
**Kết quả sau kill old writer:** < 5% failures (chỉ fail US app health)
**Kết quả post-recovery:** < 1% failures

---

## Diagram: Data Flow trong từng Phase

### Phase 1: Baseline
```
US Write │ WritePool│──→ postgres-us (global writer)
EU Write │ WritePool│──→ postgres-us (global writer)
US Read  │ ReadPool │──→ postgres-us (home region)
EU Read  │ ReadPool │──→ postgres-eu (home region)
```

### Phase 2: Topology Switch (postgres-eu = MASTER)
```
US Write │ WritePool│──→ postgres-eu (new writer via auto-discovery)
EU Write │ WritePool│──→ postgres-eu (new writer via auto-discovery)
US Read  │ ReadPool │──→ postgres-us (home region, degraded if killed)
EU Read  │ ReadPool │──→ postgres-eu (home region)
```

### Phase 3: Kill postgres-us + Recovery
```
After kill:  Writes → postgres-eu. US ReadPool degraded.
After recovery: Full restore to Phase 1 topology.
```

---

## Kết quả Test thực tế (k6 + Docker Compose)

### KỊCH BẢN: Failover Disaster (kill writer)

**Quy trình test:**
1. T+0-18s: k6 warmup + steady traffic (5→20 VUs)
2. T+18s: Update `aurora_replica_status()` → postgres-eu = MASTER
3. T+18-30s: Chờ wrapper topology refresh (clusterTopologyRefreshRateMs=5s)
4. T+30s: Kill postgres-us (old writer) bằng `docker stop`
5. T+30-40s: failover2 plugin phát hiện connection failure, reconnects
6. T+40-55s: Verify writes + reads
7. Recovery: Start postgres-us, restore topology, terminate connections

### Kết quả k6 Metrics

| Metric | Value | Result |
|--------|-------|--------|
| `http_req_failed` | **1.40%** (22/1565) | ✅ |
| `failed_requests` (custom) | **4.45%** (43/966) | ✅ Dưới 10% threshold |
| `checks_succeeded` | **97.08%** (2101/2164) | ✅ |
| `checks_failed` | **2.91%** (63/2164) | ✅ |
| `http_req_duration p(95)` | 5s | ❌ Vượt 2s threshold (failover window) |

### Phân tích failures

| Check | Tỷ lệ | Số lượng | Nguyên nhân |
|-------|-------|----------|-------------|
| Health status UP | 93% | 20 failures | Health check trong failover window |
| db connected | 93% | 20 failures | WritePool chưa reconnect kịp |
| Products returned (reads) | 99% | 1 failure | Rất ít - ReadPool ưu tiên home region |
| Product created (writes) | 96% | 2 failures | Viết bị fail trong failover window |
| Topology available | 100% | 0 failures | Luôn hoạt động |
| US app reachable | 100% | 0 failures | App process không crash |
| EU app reachable | 100% | 0 failures | App process không crash |

### Nhận xét

1. **failover2 plugin hoạt động đúng**: Khi writer bị kill, wrapper phát hiện và reconnect tới writer mới (postgres-eu).
2. **Thời gian failover**: ~5-10s (p95 latency = 10s, giảm từ 30s so với các test trước).
3. **ReadPool không bị ảnh hưởng**: ReadPool đọc từ home region (postgres-eu), không phụ thuộc vào writer.
4. **WritePool bị ảnh hưởng tạm thời**: Khi writer bị kill, WritePool mất kết nối ~5-10s cho failover.
5. **Recovery hoàn toàn**: Sau khi start postgres-us + restore topology + terminate connections, cả 2 app trở về trạng thái baseline.

### Lưu ý Architecture

- **failover2 plugin chỉ handle DISASTER failover**: Khi connection bị break (node crash), wrapper detect failure và reconnect tới writer mới.
- **Không hỗ trợ graceful topology switch**: Chỉ đổi `aurora_replica_status()` không đủ để re-route writes. Cần kill node hoặc force reconnect.
- **Giải pháp cho graceful switch**: 
  - Restart apps sau khi đổi topology
  - Hoặc dùng `pg_terminate_backend` để force HikariCP reconnect
  - Hoặc dùng EFM plugin + cluster endpoint DNS (real Aurora)
- **clusterTopologyRefreshRateMs**: Đã config 5000ms (5s) để tăng tốc detect topology change, nhưng failover2 vẫn cần connection failure để trigger re-route.

