import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = 'http://localhost:8000';
const US_URL = 'http://localhost:8080';
const EU_URL = 'http://localhost:8081';

const failRate = new Rate('failed_requests');
const healthTrend = new Trend('health_response_time');
const productTrend = new Trend('product_response_time');
const topologyTrend = new Trend('topology_response_time');

export const options = {
  stages: [
    { duration: '10s', target: 5 },   // T+0  - T+10s: warm up
    { duration: '20s', target: 20 },  // T+10 - T+30s: normal load (switchover happens at ~T+14s)
    { duration: '20s', target: 20 },  // T+30 - T+50s: during/after switchover + kill old writer
    { duration: '15s', target: 10 },  // T+50 - T+65s: recovery verification
  ],
  thresholds: {
    failed_requests: ['rate<0.10'], // max 10% failures
    http_req_duration: ['p(95)<2000'], // 95% under 2s
  },
};

const NAMES = ['Switch Test Item', 'HA Product', 'Multi-Region Widget', 'Global SKU-001', 'Global SKU-002'];

export default function () {
  group('Health Check', function () {
    const res = http.get(`${BASE_URL}/health`);
    const ok = check(res, {
      'health status is UP': (r) => {
        try { return JSON.parse(r.body).status === 'UP'; }
        catch (e) { return false; }
      },
      'db is connected': (r) => {
        try { return JSON.parse(r.body).dbConnected === true; }
        catch (e) { return false; }
      },
    });
    failRate.add(!ok);
    healthTrend.add(res.timings.duration);
  });

  group('Products API', function () {
    // Read products
    const getRes = http.get(`${BASE_URL}/api/products`);
    const getOk = check(getRes, {
      'products returned': (r) => r.status === 200,
    });
    failRate.add(!getOk);
    productTrend.add(getRes.timings.duration);

    // Write a new product (20% chance)
    if (Math.random() < 0.2) {
      const name = NAMES[Math.floor(Math.random() * NAMES.length)] + '-' + __VU + '-' + __ITER;
      const payload = JSON.stringify({ name, price: Math.round(Math.random() * 10000) / 100 });
      const createRes = http.post(`${BASE_URL}/api/products`, payload, {
        headers: { 'Content-Type': 'application/json' },
      });
      const createOk = check(createRes, {
        'product created': (r) => r.status === 200 || r.status === 201,
      });
      failRate.add(!createOk);
    }
  });

  group('Admin Topology', function () {
    const res = http.get(`${BASE_URL}/admin/topology`);
    const ok = check(res, {
      'topology available': (r) => r.status === 200,
      'has instances': (r) => {
        try { return JSON.parse(r.body).instances && JSON.parse(r.body).instances.length > 0; }
        catch (e) { return false; }
      },
    });
    failRate.add(!ok);
    topologyTrend.add(res.timings.duration);
  });

  // Also directly check both region health
  const usHealth = http.get(`${US_URL}/health`);
  const euHealth = http.get(`${EU_URL}/health`);

  check(usHealth, {
    'US app reachable': (r) => r.status === 200,
  });
  check(euHealth, {
    'EU app reachable': (r) => r.status === 200,
  });

  sleep(1);
}
