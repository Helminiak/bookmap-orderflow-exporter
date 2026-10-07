# Bookmap Orderflow Export Schema v0.1

The event file is gzip-compressed NDJSON: one JSON object per line, in callback order.

## Common fields

- `schema`: `bookmap-orderflow-v0.1`
- `seq`: monotonically increasing local event sequence
- `market_ns`: Bookmap `TimeListener` nanosecond clock, not the Windows wall clock
- `phase`: `HISTORY` until Bookmap calls `onRealtimeStart()`, then `REALTIME`
- `alias`: Bookmap instrument alias
- `event`: `MBO_ADD`, `MBO_REPLACE`, `MBO_CANCEL`, `TRADE`, or `CONTROL`

## MBO fields

- `order_id`: Bookmap/Rithmic order identifier
- `side`: `BID`, `ASK`, or `UNKNOWN` if Bookmap gives a replace/cancel for an order not previously observed by this module
- `price_level`: integer Bookmap price level; null is possible only for an anomalous cancel
- `price`: real price derived as `price_level * pips`; null if price level is unknown
- `size`: order size; null is possible only for an anomalous cancel
- `anomaly`: true if local order-state reconstruction found an inconsistency

## Trade fields

- `aggressor_side`: `BUY` when `TradeInfo.isBidAggressor`, otherwise `SELL`
- `price_level`: Bookmap tick/level coordinate (double API type)
- `price`: `price_level * pips`
- `size`
- `aggressor_order_id`: nullable
- `passive_order_id`: nullable
- `execution_start`
- `execution_end`
- `otc`

## CONTROL records

Used for module lifecycle and replay/history transitions. `control` currently includes:

- `START`
- `REALTIME_START`
- `TIME_REVERSAL`
- `STOP`

A time reversal is significant during manual replay rewinds. Bulk conversion runs should be monotonic and should not contain one.
