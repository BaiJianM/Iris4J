-- 全量导入监控：一次往返统计全部流的 xlen / pending / lag / dlq。
-- KEYS = 流名列表（135 条，禁止抽样）。
-- 返回：每流一条 "stream|xlen|pending|lag|dlq"，末尾附一条 "TOTAL|...|unknown=n"。
--
-- 为什么不用 redis-cli 逐条：docker exec 每次进程开销约 0.5-1s，405 次调用要 5 分钟以上，
-- 而本脚本一次 EVAL 即完成（EVAL 内为进程内调用）。
--
-- 解析要点：XINFO GROUPS 的组元素在 RESP2->Lua 转换后是**扁平数组**
-- [name, X, consumers, N, pending, N, last-delivered-id, ID, entries-read, N, lag, N]。
-- 其中 entries-read 可能为 nil（空 bulk）造成数组空洞，因此绝不能用 #g 或 ipairs 遍历，
-- 必须用固定下标窗口 g[1..14] 逐个比对字段名再取值 —— 空洞只影响长度、不影响直接索引。
local out = {}
local sum_xl, sum_pen, sum_lag, sum_dlq, unknown = 0, 0, 0, 0, 0
for i = 1, #KEYS do
  local k = KEYS[i]
  local xl = redis.call('XLEN', k) or 0
  local dlq = redis.call('XLEN', k .. ':dlq') or 0

  local pen, lag = 0, 0
  local ok, groups = pcall(redis.call, 'XINFO', 'GROUPS', k)
  if ok and type(groups) == 'table' then
    for _, g in ipairs(groups) do
      local gp, gl = 0, nil
      for j = 1, 14 do
        local field = g[j]
        if field == 'pending' then
          local v = g[j + 1]
          if type(v) == 'number' then gp = v end
        elseif field == 'lag' then
          local v = g[j + 1]
          if type(v) == 'number' then gl = v end
        end
      end
      pen = pen + gp
      if gl == nil then
        unknown = unknown + 1
        lag = -1
      elseif lag >= 0 then
        lag = lag + gl
      end
    end
  else
    -- 流不存在（尚无 group）或 XINFO 不被允许：记未知，避免被误判成"已追平"
    unknown = unknown + 1
    lag = -1
  end

  sum_xl = sum_xl + xl
  sum_pen = sum_pen + pen
  sum_dlq = sum_dlq + dlq
  if lag > 0 then sum_lag = sum_lag + lag end
  out[i] = k .. '|' .. xl .. '|' .. pen .. '|' .. lag .. '|' .. dlq
end

-- 稳态下的流长度上限（用于判断是否仍在灌入）：本行为便于人工核对，不参与判定
out[#out + 1] = 'TOTAL|' .. sum_xl .. '|' .. sum_pen .. '|' .. sum_lag .. '|' .. sum_dlq
    .. '|unknown=' .. unknown
return out
