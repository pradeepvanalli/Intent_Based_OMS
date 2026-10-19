-- FS-02: on-time pick. Sourcing now records, per package, the date it must leave the node (ship_by) and the node-local
-- day part; the first shipment from the node says whether the pick was on time. Node reliability learns from that,
-- not from carrier delivery (which belongs to the lane).
alter table node_assignment add column if not exists ship_by date;
alter table node_assignment add column if not exists picked_on_time boolean;

-- CX-01: the on-time probability before lane calibration (base) next to the one the order was given (stated), so the
-- calibration offset is learned against the base (observed - mean base) and the residual error after calibration
-- (observed - mean stated) can be measured against the 3-point target without the two feeding each other.
alter table node_assignment add column if not exists base_on_time double precision;
alter table lane_delivery_sample add column if not exists base_on_time double precision;
