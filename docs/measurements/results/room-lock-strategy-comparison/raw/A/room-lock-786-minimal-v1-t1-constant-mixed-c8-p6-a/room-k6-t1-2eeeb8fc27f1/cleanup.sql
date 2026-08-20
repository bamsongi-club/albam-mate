\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-2eeeb8fc27f1'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (986, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-host@example.invalid'),
    (987, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-cancel-0@example.invalid'),
    (988, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-cancel-1@example.invalid'),
    (989, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-cancel-2@example.invalid'),
    (990, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-cancel-3@example.invalid'),
    (991, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-waiter-0@example.invalid'),
    (992, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-waiter-1@example.invalid'),
    (993, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-waiter-2@example.invalid'),
    (994, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-waiter-3@example.invalid'),
    (995, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-mixed-waiter-4@example.invalid'),
    (996, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s4-host@example.invalid'),
    (997, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s4-cancel@example.invalid'),
    (998, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s4-waiter-0@example.invalid'),
    (999, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s4-waiter-1@example.invalid'),
    (1000, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s5-host@example.invalid'),
    (1001, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s5-cancel@example.invalid'),
    (1002, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s5-waiter-0@example.invalid'),
    (1003, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s5-waiter-1@example.invalid'),
    (1004, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s6-host@example.invalid'),
    (1005, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s6-cancel@example.invalid'),
    (1006, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s6-waiter-0@example.invalid'),
    (1007, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s6-waiter-1@example.invalid'),
    (1008, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s7-host@example.invalid'),
    (1009, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s7-cancel@example.invalid'),
    (1010, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s7-waiter-0@example.invalid'),
    (1011, 'room-k6.room-k6-t1-2eeeb8fc27f1.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (5221, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r0-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5222, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r0-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5223, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r0-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5224, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r0-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5225, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r0-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5226, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r1-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5227, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r1-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5228, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r1-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5229, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r1-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5230, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r1-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5231, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r2-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5232, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r2-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5233, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r2-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5234, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r2-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5235, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r2-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5236, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r3-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5237, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r3-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5238, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r3-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5239, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r3-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5240, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r3-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5241, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r4-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5242, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r4-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5243, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r4-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5244, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r4-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5245, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r4-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5246, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r5-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5247, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r5-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5248, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r5-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5249, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r5-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5250, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r5-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5251, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r6-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5252, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r6-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5253, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r6-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5254, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r6-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5255, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r6-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5256, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r7-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5257, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r7-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5258, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r7-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5259, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r7-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5260, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r7-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5261, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r8-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5262, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r8-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5263, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r8-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5264, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r8-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5265, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r8-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5266, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r9-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5267, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r9-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5268, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r9-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5269, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r9-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5270, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r9-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5271, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r10-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5272, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r10-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5273, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r10-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5274, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r10-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5275, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r10-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5276, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r11-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5277, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r11-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5278, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r11-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5279, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r11-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5280, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r11-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5281, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r12-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5282, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r12-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5283, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r12-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5284, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r12-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5285, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r12-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5286, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r13-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5287, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r13-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5288, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r13-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5289, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r13-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5290, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r13-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5291, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r14-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5292, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r14-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5293, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r14-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5294, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r14-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5295, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r14-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5296, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r15-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5297, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r15-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5298, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r15-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5299, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r15-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5300, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r15-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5301, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r16-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5302, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r16-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5303, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r16-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5304, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r16-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5305, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r16-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5306, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r17-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5307, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r17-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5308, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r17-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5309, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r17-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5310, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r17-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5311, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r18-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5312, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r18-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5313, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r18-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5314, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r18-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5315, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r18-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5316, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r19-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5317, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r19-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5318, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r19-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5319, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r19-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5320, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r19-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5321, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r20-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5322, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r20-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5323, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r20-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5324, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r20-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5325, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r20-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5326, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r21-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5327, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r21-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5328, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r21-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5329, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r21-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5330, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r21-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5331, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r22-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5332, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r22-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5333, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r22-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5334, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r22-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5335, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r22-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5336, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r23-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5337, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r23-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5338, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r23-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5339, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r23-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5340, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r23-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5341, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r24-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5342, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r24-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5343, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r24-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5344, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r24-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5345, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r24-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5346, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r25-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5347, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r25-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5348, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r25-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5349, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r25-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5350, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r25-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5351, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r26-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5352, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r26-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5353, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r26-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5354, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r26-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5355, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r26-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5356, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r27-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5357, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r27-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5358, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r27-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5359, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r27-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5360, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r27-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5361, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r28-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5362, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r28-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5363, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r28-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5364, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r28-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5365, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r28-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5366, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r29-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5367, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r29-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5368, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r29-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5369, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r29-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5370, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r29-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5371, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r30-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5372, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r30-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5373, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r30-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5374, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r30-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5375, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r30-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5376, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r31-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5377, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r31-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5378, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r31-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5379, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r31-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5380, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r31-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5381, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r32-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5382, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r32-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5383, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r32-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5384, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r32-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5385, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r32-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5386, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r33-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5387, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r33-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5388, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r33-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5389, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r33-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5390, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r33-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5391, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r34-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5392, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r34-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5393, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r34-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5394, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r34-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5395, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r34-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5396, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r35-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5397, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r35-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5398, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r35-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5399, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r35-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5400, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r35-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5401, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r36-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5402, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r36-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5403, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r36-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5404, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r36-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5405, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r36-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5406, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r37-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5407, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r37-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5408, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r37-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5409, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r37-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5410, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r37-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5411, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r38-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5412, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r38-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5413, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r38-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5414, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r38-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5415, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r38-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5416, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r39-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5417, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r39-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5418, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r39-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5419, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r39-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5420, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r39-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5421, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r40-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5422, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r40-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5423, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r40-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5424, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r40-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5425, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r40-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5426, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r41-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5427, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r41-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5428, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r41-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5429, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r41-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5430, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r41-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5431, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r42-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5432, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r42-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5433, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r42-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5434, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r42-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5435, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r42-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5436, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r43-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5437, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r43-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5438, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r43-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5439, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r43-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5440, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r43-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5441, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r44-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5442, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r44-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5443, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r44-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5444, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r44-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5445, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r44-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5446, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r45-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5447, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r45-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5448, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r45-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5449, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r45-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5450, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r45-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5451, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r46-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5452, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r46-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5453, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r46-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5454, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r46-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5455, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r46-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5456, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r47-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5457, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r47-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5458, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r47-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5459, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r47-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5460, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r47-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5461, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r48-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5462, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r48-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5463, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r48-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5464, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r48-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5465, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r48-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5466, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r49-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5467, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r49-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5468, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r49-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5469, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r49-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5470, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r49-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5471, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r50-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5472, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r50-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5473, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r50-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5474, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r50-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5475, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r50-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5476, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r51-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5477, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r51-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5478, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r51-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5479, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r51-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5480, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r51-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5481, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r52-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5482, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r52-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5483, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r52-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5484, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r52-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5485, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r52-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5486, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r53-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5487, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r53-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5488, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r53-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5489, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r53-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5490, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r53-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5491, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r54-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5492, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r54-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5493, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r54-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5494, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r54-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5495, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r54-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5496, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r55-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5497, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r55-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5498, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r55-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5499, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r55-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5500, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r55-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5501, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r56-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5502, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r56-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5503, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r56-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5504, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r56-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5505, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r56-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5506, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r57-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5507, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r57-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5508, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r57-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5509, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r57-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5510, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r57-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5511, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r58-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5512, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r58-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5513, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r58-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5514, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r58-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5515, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r58-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5516, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r59-mixed-hot', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5517, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r59-spread-s4', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5518, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r59-spread-s5', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5519, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r59-spread-s6', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe'),
    (5520, 'ROOM-K6 room-k6-t1-2eeeb8fc27f1 t1-r59-spread-s7', 'ROOM k6 fixture c33f8f57523e4216ad2b64f9d8c563fe');

DO $$
BEGIN
    -- 검증 뒤 새 파생 행이 삽입되어 삭제되지 않도록 FK 부모 행을 먼저 잠근다.
    PERFORM 1
    FROM rooms room
    JOIN room_k6_cleanup_rooms fixture ON fixture.id = room.id
    ORDER BY room.id
    FOR UPDATE OF room;
    PERFORM 1
    FROM notification_outbox_events event
    JOIN room_k6_cleanup_rooms fixture ON fixture.id = event.room_id
    ORDER BY event.id
    FOR UPDATE OF event;
    PERFORM 1
    FROM chat_rooms chat_room
    JOIN room_k6_cleanup_rooms fixture ON fixture.id = chat_room.room_id
    ORDER BY chat_room.id
    FOR UPDATE OF chat_room;

    IF (SELECT count(*) FROM users u JOIN room_k6_cleanup_users f ON f.id = u.id AND f.email = u.email)
        <> (SELECT count(*) FROM room_k6_cleanup_users) THEN
        RAISE EXCEPTION 'ROOM k6 fixture user identity mismatch';
    END IF;
    IF (SELECT count(*) FROM rooms r JOIN room_k6_cleanup_rooms f
        ON f.id = r.id AND f.title = r.title AND f.description = r.description)
        <> (SELECT count(*) FROM room_k6_cleanup_rooms) THEN
        RAISE EXCEPTION 'ROOM k6 fixture ROOM identity mismatch';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM participations participation
        JOIN room_k6_cleanup_rooms r ON r.id = participation.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = participation.user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has participation by non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM room_waitlists waitlist
        JOIN room_k6_cleanup_rooms r ON r.id = waitlist.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = waitlist.user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has waitlist by non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notifications n
        JOIN room_k6_cleanup_rooms r ON r.id = n.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = n.recipient_user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has notification for non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notifications n
        JOIN room_k6_cleanup_rooms fixture_room ON fixture_room.id = n.room_id
        JOIN notification_outbox_events source_event ON source_event.id = n.source_event_id
        WHERE source_event.room_id <> n.room_id
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has notification from another ROOM outbox event';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notification_outbox_recipients recipient
        JOIN notification_outbox_events event ON event.id = recipient.outbox_event_id
        JOIN room_k6_cleanup_rooms r ON r.id = event.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = recipient.recipient_user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has outbox recipient outside fixture users';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM chat_messages message
        JOIN chat_rooms chat_room ON chat_room.id = message.chat_room_id
        JOIN room_k6_cleanup_rooms r ON r.id = chat_room.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = message.sender_user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has chat message by non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM rooms room
        JOIN room_k6_cleanup_users u ON u.id = room.host_user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = room.id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user hosts ROOM outside fixture';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM participations participation
        JOIN room_k6_cleanup_users u ON u.id = participation.user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = participation.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has participation outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM room_waitlists waitlist
        JOIN room_k6_cleanup_users u ON u.id = waitlist.user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = waitlist.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has waitlist outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notifications n
        JOIN room_k6_cleanup_users u ON u.id = n.recipient_user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = n.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has notification outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notification_outbox_recipients recipient
        JOIN room_k6_cleanup_users u ON u.id = recipient.recipient_user_id
        JOIN notification_outbox_events event ON event.id = recipient.outbox_event_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = event.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has outbox recipient outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM chat_messages message
        JOIN room_k6_cleanup_users u ON u.id = message.sender_user_id
        JOIN chat_rooms chat_room ON chat_room.id = message.chat_room_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = chat_room.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has chat message outside fixture ROOM';
    END IF;
    IF EXISTS (SELECT 1 FROM social_accounts account JOIN room_k6_cleanup_users u ON u.id = account.user_id) THEN
        RAISE EXCEPTION 'fixture user has social account';
    END IF;
    IF EXISTS (SELECT 1 FROM user_played_games played JOIN room_k6_cleanup_users u ON u.id = played.user_id) THEN
        RAISE EXCEPTION 'fixture user has played game';
    END IF;
END $$;

DELETE FROM notifications WHERE room_id IN (5221, 5222, 5223, 5224, 5225, 5226, 5227, 5228, 5229, 5230, 5231, 5232, 5233, 5234, 5235, 5236, 5237, 5238, 5239, 5240, 5241, 5242, 5243, 5244, 5245, 5246, 5247, 5248, 5249, 5250, 5251, 5252, 5253, 5254, 5255, 5256, 5257, 5258, 5259, 5260, 5261, 5262, 5263, 5264, 5265, 5266, 5267, 5268, 5269, 5270, 5271, 5272, 5273, 5274, 5275, 5276, 5277, 5278, 5279, 5280, 5281, 5282, 5283, 5284, 5285, 5286, 5287, 5288, 5289, 5290, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5305, 5306, 5307, 5308, 5309, 5310, 5311, 5312, 5313, 5314, 5315, 5316, 5317, 5318, 5319, 5320, 5321, 5322, 5323, 5324, 5325, 5326, 5327, 5328, 5329, 5330, 5331, 5332, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340, 5341, 5342, 5343, 5344, 5345, 5346, 5347, 5348, 5349, 5350, 5351, 5352, 5353, 5354, 5355, 5356, 5357, 5358, 5359, 5360, 5361, 5362, 5363, 5364, 5365, 5366, 5367, 5368, 5369, 5370, 5371, 5372, 5373, 5374, 5375, 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383, 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391, 5392, 5393, 5394, 5395, 5396, 5397, 5398, 5399, 5400, 5401, 5402, 5403, 5404, 5405, 5406, 5407, 5408, 5409, 5410, 5411, 5412, 5413, 5414, 5415, 5416, 5417, 5418, 5419, 5420, 5421, 5422, 5423, 5424, 5425, 5426, 5427, 5428, 5429, 5430, 5431, 5432, 5433, 5434, 5435, 5436, 5437, 5438, 5439, 5440, 5441, 5442, 5443, 5444, 5445, 5446, 5447, 5448, 5449, 5450, 5451, 5452, 5453, 5454, 5455, 5456, 5457, 5458, 5459, 5460, 5461, 5462, 5463, 5464, 5465, 5466, 5467, 5468, 5469, 5470, 5471, 5472, 5473, 5474, 5475, 5476, 5477, 5478, 5479, 5480, 5481, 5482, 5483, 5484, 5485, 5486, 5487, 5488, 5489, 5490, 5491, 5492, 5493, 5494, 5495, 5496, 5497, 5498, 5499, 5500, 5501, 5502, 5503, 5504, 5505, 5506, 5507, 5508, 5509, 5510, 5511, 5512, 5513, 5514, 5515, 5516, 5517, 5518, 5519, 5520);
DELETE FROM notification_outbox_events WHERE room_id IN (5221, 5222, 5223, 5224, 5225, 5226, 5227, 5228, 5229, 5230, 5231, 5232, 5233, 5234, 5235, 5236, 5237, 5238, 5239, 5240, 5241, 5242, 5243, 5244, 5245, 5246, 5247, 5248, 5249, 5250, 5251, 5252, 5253, 5254, 5255, 5256, 5257, 5258, 5259, 5260, 5261, 5262, 5263, 5264, 5265, 5266, 5267, 5268, 5269, 5270, 5271, 5272, 5273, 5274, 5275, 5276, 5277, 5278, 5279, 5280, 5281, 5282, 5283, 5284, 5285, 5286, 5287, 5288, 5289, 5290, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5305, 5306, 5307, 5308, 5309, 5310, 5311, 5312, 5313, 5314, 5315, 5316, 5317, 5318, 5319, 5320, 5321, 5322, 5323, 5324, 5325, 5326, 5327, 5328, 5329, 5330, 5331, 5332, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340, 5341, 5342, 5343, 5344, 5345, 5346, 5347, 5348, 5349, 5350, 5351, 5352, 5353, 5354, 5355, 5356, 5357, 5358, 5359, 5360, 5361, 5362, 5363, 5364, 5365, 5366, 5367, 5368, 5369, 5370, 5371, 5372, 5373, 5374, 5375, 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383, 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391, 5392, 5393, 5394, 5395, 5396, 5397, 5398, 5399, 5400, 5401, 5402, 5403, 5404, 5405, 5406, 5407, 5408, 5409, 5410, 5411, 5412, 5413, 5414, 5415, 5416, 5417, 5418, 5419, 5420, 5421, 5422, 5423, 5424, 5425, 5426, 5427, 5428, 5429, 5430, 5431, 5432, 5433, 5434, 5435, 5436, 5437, 5438, 5439, 5440, 5441, 5442, 5443, 5444, 5445, 5446, 5447, 5448, 5449, 5450, 5451, 5452, 5453, 5454, 5455, 5456, 5457, 5458, 5459, 5460, 5461, 5462, 5463, 5464, 5465, 5466, 5467, 5468, 5469, 5470, 5471, 5472, 5473, 5474, 5475, 5476, 5477, 5478, 5479, 5480, 5481, 5482, 5483, 5484, 5485, 5486, 5487, 5488, 5489, 5490, 5491, 5492, 5493, 5494, 5495, 5496, 5497, 5498, 5499, 5500, 5501, 5502, 5503, 5504, 5505, 5506, 5507, 5508, 5509, 5510, 5511, 5512, 5513, 5514, 5515, 5516, 5517, 5518, 5519, 5520);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (5221, 5222, 5223, 5224, 5225, 5226, 5227, 5228, 5229, 5230, 5231, 5232, 5233, 5234, 5235, 5236, 5237, 5238, 5239, 5240, 5241, 5242, 5243, 5244, 5245, 5246, 5247, 5248, 5249, 5250, 5251, 5252, 5253, 5254, 5255, 5256, 5257, 5258, 5259, 5260, 5261, 5262, 5263, 5264, 5265, 5266, 5267, 5268, 5269, 5270, 5271, 5272, 5273, 5274, 5275, 5276, 5277, 5278, 5279, 5280, 5281, 5282, 5283, 5284, 5285, 5286, 5287, 5288, 5289, 5290, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5305, 5306, 5307, 5308, 5309, 5310, 5311, 5312, 5313, 5314, 5315, 5316, 5317, 5318, 5319, 5320, 5321, 5322, 5323, 5324, 5325, 5326, 5327, 5328, 5329, 5330, 5331, 5332, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340, 5341, 5342, 5343, 5344, 5345, 5346, 5347, 5348, 5349, 5350, 5351, 5352, 5353, 5354, 5355, 5356, 5357, 5358, 5359, 5360, 5361, 5362, 5363, 5364, 5365, 5366, 5367, 5368, 5369, 5370, 5371, 5372, 5373, 5374, 5375, 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383, 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391, 5392, 5393, 5394, 5395, 5396, 5397, 5398, 5399, 5400, 5401, 5402, 5403, 5404, 5405, 5406, 5407, 5408, 5409, 5410, 5411, 5412, 5413, 5414, 5415, 5416, 5417, 5418, 5419, 5420, 5421, 5422, 5423, 5424, 5425, 5426, 5427, 5428, 5429, 5430, 5431, 5432, 5433, 5434, 5435, 5436, 5437, 5438, 5439, 5440, 5441, 5442, 5443, 5444, 5445, 5446, 5447, 5448, 5449, 5450, 5451, 5452, 5453, 5454, 5455, 5456, 5457, 5458, 5459, 5460, 5461, 5462, 5463, 5464, 5465, 5466, 5467, 5468, 5469, 5470, 5471, 5472, 5473, 5474, 5475, 5476, 5477, 5478, 5479, 5480, 5481, 5482, 5483, 5484, 5485, 5486, 5487, 5488, 5489, 5490, 5491, 5492, 5493, 5494, 5495, 5496, 5497, 5498, 5499, 5500, 5501, 5502, 5503, 5504, 5505, 5506, 5507, 5508, 5509, 5510, 5511, 5512, 5513, 5514, 5515, 5516, 5517, 5518, 5519, 5520)
);
DELETE FROM chat_rooms WHERE room_id IN (5221, 5222, 5223, 5224, 5225, 5226, 5227, 5228, 5229, 5230, 5231, 5232, 5233, 5234, 5235, 5236, 5237, 5238, 5239, 5240, 5241, 5242, 5243, 5244, 5245, 5246, 5247, 5248, 5249, 5250, 5251, 5252, 5253, 5254, 5255, 5256, 5257, 5258, 5259, 5260, 5261, 5262, 5263, 5264, 5265, 5266, 5267, 5268, 5269, 5270, 5271, 5272, 5273, 5274, 5275, 5276, 5277, 5278, 5279, 5280, 5281, 5282, 5283, 5284, 5285, 5286, 5287, 5288, 5289, 5290, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5305, 5306, 5307, 5308, 5309, 5310, 5311, 5312, 5313, 5314, 5315, 5316, 5317, 5318, 5319, 5320, 5321, 5322, 5323, 5324, 5325, 5326, 5327, 5328, 5329, 5330, 5331, 5332, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340, 5341, 5342, 5343, 5344, 5345, 5346, 5347, 5348, 5349, 5350, 5351, 5352, 5353, 5354, 5355, 5356, 5357, 5358, 5359, 5360, 5361, 5362, 5363, 5364, 5365, 5366, 5367, 5368, 5369, 5370, 5371, 5372, 5373, 5374, 5375, 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383, 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391, 5392, 5393, 5394, 5395, 5396, 5397, 5398, 5399, 5400, 5401, 5402, 5403, 5404, 5405, 5406, 5407, 5408, 5409, 5410, 5411, 5412, 5413, 5414, 5415, 5416, 5417, 5418, 5419, 5420, 5421, 5422, 5423, 5424, 5425, 5426, 5427, 5428, 5429, 5430, 5431, 5432, 5433, 5434, 5435, 5436, 5437, 5438, 5439, 5440, 5441, 5442, 5443, 5444, 5445, 5446, 5447, 5448, 5449, 5450, 5451, 5452, 5453, 5454, 5455, 5456, 5457, 5458, 5459, 5460, 5461, 5462, 5463, 5464, 5465, 5466, 5467, 5468, 5469, 5470, 5471, 5472, 5473, 5474, 5475, 5476, 5477, 5478, 5479, 5480, 5481, 5482, 5483, 5484, 5485, 5486, 5487, 5488, 5489, 5490, 5491, 5492, 5493, 5494, 5495, 5496, 5497, 5498, 5499, 5500, 5501, 5502, 5503, 5504, 5505, 5506, 5507, 5508, 5509, 5510, 5511, 5512, 5513, 5514, 5515, 5516, 5517, 5518, 5519, 5520);
DELETE FROM room_waitlists WHERE room_id IN (5221, 5222, 5223, 5224, 5225, 5226, 5227, 5228, 5229, 5230, 5231, 5232, 5233, 5234, 5235, 5236, 5237, 5238, 5239, 5240, 5241, 5242, 5243, 5244, 5245, 5246, 5247, 5248, 5249, 5250, 5251, 5252, 5253, 5254, 5255, 5256, 5257, 5258, 5259, 5260, 5261, 5262, 5263, 5264, 5265, 5266, 5267, 5268, 5269, 5270, 5271, 5272, 5273, 5274, 5275, 5276, 5277, 5278, 5279, 5280, 5281, 5282, 5283, 5284, 5285, 5286, 5287, 5288, 5289, 5290, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5305, 5306, 5307, 5308, 5309, 5310, 5311, 5312, 5313, 5314, 5315, 5316, 5317, 5318, 5319, 5320, 5321, 5322, 5323, 5324, 5325, 5326, 5327, 5328, 5329, 5330, 5331, 5332, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340, 5341, 5342, 5343, 5344, 5345, 5346, 5347, 5348, 5349, 5350, 5351, 5352, 5353, 5354, 5355, 5356, 5357, 5358, 5359, 5360, 5361, 5362, 5363, 5364, 5365, 5366, 5367, 5368, 5369, 5370, 5371, 5372, 5373, 5374, 5375, 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383, 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391, 5392, 5393, 5394, 5395, 5396, 5397, 5398, 5399, 5400, 5401, 5402, 5403, 5404, 5405, 5406, 5407, 5408, 5409, 5410, 5411, 5412, 5413, 5414, 5415, 5416, 5417, 5418, 5419, 5420, 5421, 5422, 5423, 5424, 5425, 5426, 5427, 5428, 5429, 5430, 5431, 5432, 5433, 5434, 5435, 5436, 5437, 5438, 5439, 5440, 5441, 5442, 5443, 5444, 5445, 5446, 5447, 5448, 5449, 5450, 5451, 5452, 5453, 5454, 5455, 5456, 5457, 5458, 5459, 5460, 5461, 5462, 5463, 5464, 5465, 5466, 5467, 5468, 5469, 5470, 5471, 5472, 5473, 5474, 5475, 5476, 5477, 5478, 5479, 5480, 5481, 5482, 5483, 5484, 5485, 5486, 5487, 5488, 5489, 5490, 5491, 5492, 5493, 5494, 5495, 5496, 5497, 5498, 5499, 5500, 5501, 5502, 5503, 5504, 5505, 5506, 5507, 5508, 5509, 5510, 5511, 5512, 5513, 5514, 5515, 5516, 5517, 5518, 5519, 5520);
DELETE FROM participations WHERE room_id IN (5221, 5222, 5223, 5224, 5225, 5226, 5227, 5228, 5229, 5230, 5231, 5232, 5233, 5234, 5235, 5236, 5237, 5238, 5239, 5240, 5241, 5242, 5243, 5244, 5245, 5246, 5247, 5248, 5249, 5250, 5251, 5252, 5253, 5254, 5255, 5256, 5257, 5258, 5259, 5260, 5261, 5262, 5263, 5264, 5265, 5266, 5267, 5268, 5269, 5270, 5271, 5272, 5273, 5274, 5275, 5276, 5277, 5278, 5279, 5280, 5281, 5282, 5283, 5284, 5285, 5286, 5287, 5288, 5289, 5290, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5305, 5306, 5307, 5308, 5309, 5310, 5311, 5312, 5313, 5314, 5315, 5316, 5317, 5318, 5319, 5320, 5321, 5322, 5323, 5324, 5325, 5326, 5327, 5328, 5329, 5330, 5331, 5332, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340, 5341, 5342, 5343, 5344, 5345, 5346, 5347, 5348, 5349, 5350, 5351, 5352, 5353, 5354, 5355, 5356, 5357, 5358, 5359, 5360, 5361, 5362, 5363, 5364, 5365, 5366, 5367, 5368, 5369, 5370, 5371, 5372, 5373, 5374, 5375, 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383, 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391, 5392, 5393, 5394, 5395, 5396, 5397, 5398, 5399, 5400, 5401, 5402, 5403, 5404, 5405, 5406, 5407, 5408, 5409, 5410, 5411, 5412, 5413, 5414, 5415, 5416, 5417, 5418, 5419, 5420, 5421, 5422, 5423, 5424, 5425, 5426, 5427, 5428, 5429, 5430, 5431, 5432, 5433, 5434, 5435, 5436, 5437, 5438, 5439, 5440, 5441, 5442, 5443, 5444, 5445, 5446, 5447, 5448, 5449, 5450, 5451, 5452, 5453, 5454, 5455, 5456, 5457, 5458, 5459, 5460, 5461, 5462, 5463, 5464, 5465, 5466, 5467, 5468, 5469, 5470, 5471, 5472, 5473, 5474, 5475, 5476, 5477, 5478, 5479, 5480, 5481, 5482, 5483, 5484, 5485, 5486, 5487, 5488, 5489, 5490, 5491, 5492, 5493, 5494, 5495, 5496, 5497, 5498, 5499, 5500, 5501, 5502, 5503, 5504, 5505, 5506, 5507, 5508, 5509, 5510, 5511, 5512, 5513, 5514, 5515, 5516, 5517, 5518, 5519, 5520);
DELETE FROM rooms WHERE id IN (5221, 5222, 5223, 5224, 5225, 5226, 5227, 5228, 5229, 5230, 5231, 5232, 5233, 5234, 5235, 5236, 5237, 5238, 5239, 5240, 5241, 5242, 5243, 5244, 5245, 5246, 5247, 5248, 5249, 5250, 5251, 5252, 5253, 5254, 5255, 5256, 5257, 5258, 5259, 5260, 5261, 5262, 5263, 5264, 5265, 5266, 5267, 5268, 5269, 5270, 5271, 5272, 5273, 5274, 5275, 5276, 5277, 5278, 5279, 5280, 5281, 5282, 5283, 5284, 5285, 5286, 5287, 5288, 5289, 5290, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5305, 5306, 5307, 5308, 5309, 5310, 5311, 5312, 5313, 5314, 5315, 5316, 5317, 5318, 5319, 5320, 5321, 5322, 5323, 5324, 5325, 5326, 5327, 5328, 5329, 5330, 5331, 5332, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340, 5341, 5342, 5343, 5344, 5345, 5346, 5347, 5348, 5349, 5350, 5351, 5352, 5353, 5354, 5355, 5356, 5357, 5358, 5359, 5360, 5361, 5362, 5363, 5364, 5365, 5366, 5367, 5368, 5369, 5370, 5371, 5372, 5373, 5374, 5375, 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383, 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391, 5392, 5393, 5394, 5395, 5396, 5397, 5398, 5399, 5400, 5401, 5402, 5403, 5404, 5405, 5406, 5407, 5408, 5409, 5410, 5411, 5412, 5413, 5414, 5415, 5416, 5417, 5418, 5419, 5420, 5421, 5422, 5423, 5424, 5425, 5426, 5427, 5428, 5429, 5430, 5431, 5432, 5433, 5434, 5435, 5436, 5437, 5438, 5439, 5440, 5441, 5442, 5443, 5444, 5445, 5446, 5447, 5448, 5449, 5450, 5451, 5452, 5453, 5454, 5455, 5456, 5457, 5458, 5459, 5460, 5461, 5462, 5463, 5464, 5465, 5466, 5467, 5468, 5469, 5470, 5471, 5472, 5473, 5474, 5475, 5476, 5477, 5478, 5479, 5480, 5481, 5482, 5483, 5484, 5485, 5486, 5487, 5488, 5489, 5490, 5491, 5492, 5493, 5494, 5495, 5496, 5497, 5498, 5499, 5500, 5501, 5502, 5503, 5504, 5505, 5506, 5507, 5508, 5509, 5510, 5511, 5512, 5513, 5514, 5515, 5516, 5517, 5518, 5519, 5520);
DELETE FROM users WHERE id IN (986, 987, 988, 989, 990, 991, 992, 993, 994, 995, 996, 997, 998, 999, 1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011);

COMMIT;
