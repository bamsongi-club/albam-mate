\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-4e02929cfb74'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (1012, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-host@example.invalid'),
    (1013, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-cancel-0@example.invalid'),
    (1014, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-cancel-1@example.invalid'),
    (1015, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-cancel-2@example.invalid'),
    (1016, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-cancel-3@example.invalid'),
    (1017, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-waiter-0@example.invalid'),
    (1018, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-waiter-1@example.invalid'),
    (1019, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-waiter-2@example.invalid'),
    (1020, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-waiter-3@example.invalid'),
    (1021, 'room-k6.room-k6-t1-4e02929cfb74.t1-mixed-waiter-4@example.invalid'),
    (1022, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s4-host@example.invalid'),
    (1023, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s4-cancel@example.invalid'),
    (1024, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s4-waiter-0@example.invalid'),
    (1025, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s4-waiter-1@example.invalid'),
    (1026, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s5-host@example.invalid'),
    (1027, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s5-cancel@example.invalid'),
    (1028, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s5-waiter-0@example.invalid'),
    (1029, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s5-waiter-1@example.invalid'),
    (1030, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s6-host@example.invalid'),
    (1031, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s6-cancel@example.invalid'),
    (1032, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s6-waiter-0@example.invalid'),
    (1033, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s6-waiter-1@example.invalid'),
    (1034, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s7-host@example.invalid'),
    (1035, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s7-cancel@example.invalid'),
    (1036, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s7-waiter-0@example.invalid'),
    (1037, 'room-k6.room-k6-t1-4e02929cfb74.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (5521, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r0-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5522, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r0-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5523, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r0-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5524, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r0-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5525, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r0-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5526, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r1-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5527, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r1-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5528, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r1-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5529, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r1-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5530, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r1-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5531, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r2-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5532, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r2-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5533, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r2-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5534, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r2-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5535, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r2-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5536, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r3-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5537, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r3-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5538, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r3-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5539, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r3-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5540, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r3-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5541, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r4-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5542, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r4-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5543, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r4-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5544, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r4-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5545, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r4-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5546, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r5-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5547, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r5-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5548, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r5-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5549, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r5-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5550, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r5-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5551, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r6-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5552, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r6-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5553, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r6-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5554, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r6-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5555, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r6-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5556, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r7-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5557, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r7-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5558, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r7-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5559, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r7-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5560, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r7-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5561, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r8-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5562, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r8-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5563, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r8-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5564, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r8-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5565, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r8-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5566, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r9-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5567, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r9-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5568, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r9-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5569, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r9-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5570, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r9-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5571, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r10-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5572, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r10-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5573, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r10-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5574, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r10-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5575, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r10-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5576, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r11-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5577, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r11-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5578, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r11-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5579, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r11-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5580, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r11-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5581, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r12-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5582, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r12-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5583, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r12-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5584, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r12-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5585, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r12-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5586, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r13-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5587, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r13-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5588, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r13-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5589, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r13-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5590, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r13-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5591, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r14-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5592, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r14-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5593, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r14-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5594, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r14-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5595, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r14-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5596, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r15-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5597, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r15-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5598, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r15-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5599, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r15-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5600, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r15-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5601, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r16-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5602, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r16-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5603, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r16-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5604, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r16-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5605, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r16-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5606, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r17-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5607, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r17-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5608, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r17-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5609, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r17-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5610, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r17-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5611, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r18-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5612, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r18-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5613, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r18-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5614, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r18-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5615, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r18-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5616, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r19-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5617, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r19-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5618, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r19-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5619, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r19-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5620, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r19-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5621, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r20-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5622, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r20-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5623, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r20-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5624, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r20-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5625, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r20-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5626, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r21-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5627, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r21-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5628, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r21-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5629, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r21-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5630, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r21-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5631, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r22-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5632, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r22-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5633, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r22-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5634, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r22-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5635, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r22-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5636, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r23-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5637, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r23-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5638, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r23-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5639, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r23-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5640, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r23-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5641, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r24-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5642, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r24-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5643, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r24-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5644, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r24-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5645, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r24-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5646, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r25-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5647, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r25-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5648, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r25-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5649, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r25-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5650, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r25-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5651, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r26-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5652, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r26-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5653, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r26-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5654, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r26-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5655, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r26-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5656, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r27-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5657, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r27-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5658, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r27-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5659, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r27-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5660, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r27-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5661, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r28-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5662, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r28-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5663, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r28-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5664, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r28-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5665, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r28-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5666, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r29-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5667, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r29-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5668, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r29-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5669, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r29-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5670, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r29-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5671, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r30-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5672, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r30-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5673, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r30-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5674, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r30-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5675, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r30-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5676, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r31-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5677, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r31-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5678, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r31-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5679, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r31-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5680, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r31-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5681, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r32-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5682, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r32-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5683, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r32-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5684, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r32-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5685, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r32-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5686, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r33-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5687, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r33-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5688, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r33-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5689, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r33-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5690, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r33-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5691, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r34-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5692, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r34-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5693, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r34-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5694, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r34-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5695, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r34-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5696, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r35-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5697, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r35-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5698, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r35-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5699, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r35-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5700, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r35-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5701, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r36-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5702, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r36-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5703, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r36-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5704, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r36-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5705, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r36-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5706, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r37-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5707, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r37-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5708, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r37-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5709, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r37-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5710, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r37-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5711, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r38-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5712, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r38-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5713, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r38-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5714, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r38-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5715, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r38-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5716, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r39-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5717, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r39-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5718, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r39-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5719, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r39-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5720, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r39-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5721, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r40-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5722, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r40-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5723, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r40-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5724, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r40-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5725, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r40-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5726, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r41-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5727, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r41-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5728, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r41-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5729, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r41-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5730, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r41-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5731, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r42-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5732, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r42-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5733, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r42-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5734, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r42-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5735, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r42-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5736, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r43-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5737, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r43-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5738, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r43-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5739, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r43-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5740, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r43-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5741, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r44-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5742, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r44-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5743, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r44-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5744, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r44-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5745, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r44-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5746, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r45-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5747, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r45-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5748, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r45-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5749, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r45-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5750, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r45-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5751, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r46-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5752, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r46-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5753, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r46-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5754, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r46-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5755, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r46-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5756, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r47-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5757, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r47-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5758, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r47-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5759, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r47-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5760, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r47-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5761, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r48-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5762, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r48-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5763, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r48-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5764, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r48-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5765, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r48-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5766, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r49-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5767, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r49-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5768, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r49-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5769, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r49-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5770, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r49-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5771, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r50-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5772, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r50-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5773, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r50-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5774, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r50-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5775, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r50-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5776, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r51-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5777, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r51-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5778, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r51-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5779, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r51-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5780, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r51-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5781, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r52-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5782, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r52-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5783, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r52-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5784, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r52-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5785, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r52-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5786, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r53-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5787, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r53-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5788, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r53-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5789, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r53-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5790, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r53-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5791, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r54-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5792, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r54-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5793, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r54-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5794, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r54-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5795, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r54-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5796, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r55-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5797, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r55-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5798, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r55-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5799, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r55-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5800, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r55-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5801, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r56-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5802, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r56-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5803, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r56-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5804, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r56-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5805, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r56-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5806, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r57-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5807, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r57-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5808, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r57-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5809, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r57-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5810, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r57-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5811, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r58-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5812, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r58-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5813, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r58-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5814, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r58-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5815, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r58-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5816, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r59-mixed-hot', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5817, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r59-spread-s4', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5818, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r59-spread-s5', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5819, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r59-spread-s6', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4'),
    (5820, 'ROOM-K6 room-k6-t1-4e02929cfb74 t1-r59-spread-s7', 'ROOM k6 fixture 4be5ccc4b19b4d378515d0945d26ebf4');

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

DELETE FROM notifications WHERE room_id IN (5521, 5522, 5523, 5524, 5525, 5526, 5527, 5528, 5529, 5530, 5531, 5532, 5533, 5534, 5535, 5536, 5537, 5538, 5539, 5540, 5541, 5542, 5543, 5544, 5545, 5546, 5547, 5548, 5549, 5550, 5551, 5552, 5553, 5554, 5555, 5556, 5557, 5558, 5559, 5560, 5561, 5562, 5563, 5564, 5565, 5566, 5567, 5568, 5569, 5570, 5571, 5572, 5573, 5574, 5575, 5576, 5577, 5578, 5579, 5580, 5581, 5582, 5583, 5584, 5585, 5586, 5587, 5588, 5589, 5590, 5591, 5592, 5593, 5594, 5595, 5596, 5597, 5598, 5599, 5600, 5601, 5602, 5603, 5604, 5605, 5606, 5607, 5608, 5609, 5610, 5611, 5612, 5613, 5614, 5615, 5616, 5617, 5618, 5619, 5620, 5621, 5622, 5623, 5624, 5625, 5626, 5627, 5628, 5629, 5630, 5631, 5632, 5633, 5634, 5635, 5636, 5637, 5638, 5639, 5640, 5641, 5642, 5643, 5644, 5645, 5646, 5647, 5648, 5649, 5650, 5651, 5652, 5653, 5654, 5655, 5656, 5657, 5658, 5659, 5660, 5661, 5662, 5663, 5664, 5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680, 5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 5701, 5702, 5703, 5704, 5705, 5706, 5707, 5708, 5709, 5710, 5711, 5712, 5713, 5714, 5715, 5716, 5717, 5718, 5719, 5720, 5721, 5722, 5723, 5724, 5725, 5726, 5727, 5728, 5729, 5730, 5731, 5732, 5733, 5734, 5735, 5736, 5737, 5738, 5739, 5740, 5741, 5742, 5743, 5744, 5745, 5746, 5747, 5748, 5749, 5750, 5751, 5752, 5753, 5754, 5755, 5756, 5757, 5758, 5759, 5760, 5761, 5762, 5763, 5764, 5765, 5766, 5767, 5768, 5769, 5770, 5771, 5772, 5773, 5774, 5775, 5776, 5777, 5778, 5779, 5780, 5781, 5782, 5783, 5784, 5785, 5786, 5787, 5788, 5789, 5790, 5791, 5792, 5793, 5794, 5795, 5796, 5797, 5798, 5799, 5800, 5801, 5802, 5803, 5804, 5805, 5806, 5807, 5808, 5809, 5810, 5811, 5812, 5813, 5814, 5815, 5816, 5817, 5818, 5819, 5820);
DELETE FROM notification_outbox_events WHERE room_id IN (5521, 5522, 5523, 5524, 5525, 5526, 5527, 5528, 5529, 5530, 5531, 5532, 5533, 5534, 5535, 5536, 5537, 5538, 5539, 5540, 5541, 5542, 5543, 5544, 5545, 5546, 5547, 5548, 5549, 5550, 5551, 5552, 5553, 5554, 5555, 5556, 5557, 5558, 5559, 5560, 5561, 5562, 5563, 5564, 5565, 5566, 5567, 5568, 5569, 5570, 5571, 5572, 5573, 5574, 5575, 5576, 5577, 5578, 5579, 5580, 5581, 5582, 5583, 5584, 5585, 5586, 5587, 5588, 5589, 5590, 5591, 5592, 5593, 5594, 5595, 5596, 5597, 5598, 5599, 5600, 5601, 5602, 5603, 5604, 5605, 5606, 5607, 5608, 5609, 5610, 5611, 5612, 5613, 5614, 5615, 5616, 5617, 5618, 5619, 5620, 5621, 5622, 5623, 5624, 5625, 5626, 5627, 5628, 5629, 5630, 5631, 5632, 5633, 5634, 5635, 5636, 5637, 5638, 5639, 5640, 5641, 5642, 5643, 5644, 5645, 5646, 5647, 5648, 5649, 5650, 5651, 5652, 5653, 5654, 5655, 5656, 5657, 5658, 5659, 5660, 5661, 5662, 5663, 5664, 5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680, 5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 5701, 5702, 5703, 5704, 5705, 5706, 5707, 5708, 5709, 5710, 5711, 5712, 5713, 5714, 5715, 5716, 5717, 5718, 5719, 5720, 5721, 5722, 5723, 5724, 5725, 5726, 5727, 5728, 5729, 5730, 5731, 5732, 5733, 5734, 5735, 5736, 5737, 5738, 5739, 5740, 5741, 5742, 5743, 5744, 5745, 5746, 5747, 5748, 5749, 5750, 5751, 5752, 5753, 5754, 5755, 5756, 5757, 5758, 5759, 5760, 5761, 5762, 5763, 5764, 5765, 5766, 5767, 5768, 5769, 5770, 5771, 5772, 5773, 5774, 5775, 5776, 5777, 5778, 5779, 5780, 5781, 5782, 5783, 5784, 5785, 5786, 5787, 5788, 5789, 5790, 5791, 5792, 5793, 5794, 5795, 5796, 5797, 5798, 5799, 5800, 5801, 5802, 5803, 5804, 5805, 5806, 5807, 5808, 5809, 5810, 5811, 5812, 5813, 5814, 5815, 5816, 5817, 5818, 5819, 5820);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (5521, 5522, 5523, 5524, 5525, 5526, 5527, 5528, 5529, 5530, 5531, 5532, 5533, 5534, 5535, 5536, 5537, 5538, 5539, 5540, 5541, 5542, 5543, 5544, 5545, 5546, 5547, 5548, 5549, 5550, 5551, 5552, 5553, 5554, 5555, 5556, 5557, 5558, 5559, 5560, 5561, 5562, 5563, 5564, 5565, 5566, 5567, 5568, 5569, 5570, 5571, 5572, 5573, 5574, 5575, 5576, 5577, 5578, 5579, 5580, 5581, 5582, 5583, 5584, 5585, 5586, 5587, 5588, 5589, 5590, 5591, 5592, 5593, 5594, 5595, 5596, 5597, 5598, 5599, 5600, 5601, 5602, 5603, 5604, 5605, 5606, 5607, 5608, 5609, 5610, 5611, 5612, 5613, 5614, 5615, 5616, 5617, 5618, 5619, 5620, 5621, 5622, 5623, 5624, 5625, 5626, 5627, 5628, 5629, 5630, 5631, 5632, 5633, 5634, 5635, 5636, 5637, 5638, 5639, 5640, 5641, 5642, 5643, 5644, 5645, 5646, 5647, 5648, 5649, 5650, 5651, 5652, 5653, 5654, 5655, 5656, 5657, 5658, 5659, 5660, 5661, 5662, 5663, 5664, 5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680, 5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 5701, 5702, 5703, 5704, 5705, 5706, 5707, 5708, 5709, 5710, 5711, 5712, 5713, 5714, 5715, 5716, 5717, 5718, 5719, 5720, 5721, 5722, 5723, 5724, 5725, 5726, 5727, 5728, 5729, 5730, 5731, 5732, 5733, 5734, 5735, 5736, 5737, 5738, 5739, 5740, 5741, 5742, 5743, 5744, 5745, 5746, 5747, 5748, 5749, 5750, 5751, 5752, 5753, 5754, 5755, 5756, 5757, 5758, 5759, 5760, 5761, 5762, 5763, 5764, 5765, 5766, 5767, 5768, 5769, 5770, 5771, 5772, 5773, 5774, 5775, 5776, 5777, 5778, 5779, 5780, 5781, 5782, 5783, 5784, 5785, 5786, 5787, 5788, 5789, 5790, 5791, 5792, 5793, 5794, 5795, 5796, 5797, 5798, 5799, 5800, 5801, 5802, 5803, 5804, 5805, 5806, 5807, 5808, 5809, 5810, 5811, 5812, 5813, 5814, 5815, 5816, 5817, 5818, 5819, 5820)
);
DELETE FROM chat_rooms WHERE room_id IN (5521, 5522, 5523, 5524, 5525, 5526, 5527, 5528, 5529, 5530, 5531, 5532, 5533, 5534, 5535, 5536, 5537, 5538, 5539, 5540, 5541, 5542, 5543, 5544, 5545, 5546, 5547, 5548, 5549, 5550, 5551, 5552, 5553, 5554, 5555, 5556, 5557, 5558, 5559, 5560, 5561, 5562, 5563, 5564, 5565, 5566, 5567, 5568, 5569, 5570, 5571, 5572, 5573, 5574, 5575, 5576, 5577, 5578, 5579, 5580, 5581, 5582, 5583, 5584, 5585, 5586, 5587, 5588, 5589, 5590, 5591, 5592, 5593, 5594, 5595, 5596, 5597, 5598, 5599, 5600, 5601, 5602, 5603, 5604, 5605, 5606, 5607, 5608, 5609, 5610, 5611, 5612, 5613, 5614, 5615, 5616, 5617, 5618, 5619, 5620, 5621, 5622, 5623, 5624, 5625, 5626, 5627, 5628, 5629, 5630, 5631, 5632, 5633, 5634, 5635, 5636, 5637, 5638, 5639, 5640, 5641, 5642, 5643, 5644, 5645, 5646, 5647, 5648, 5649, 5650, 5651, 5652, 5653, 5654, 5655, 5656, 5657, 5658, 5659, 5660, 5661, 5662, 5663, 5664, 5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680, 5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 5701, 5702, 5703, 5704, 5705, 5706, 5707, 5708, 5709, 5710, 5711, 5712, 5713, 5714, 5715, 5716, 5717, 5718, 5719, 5720, 5721, 5722, 5723, 5724, 5725, 5726, 5727, 5728, 5729, 5730, 5731, 5732, 5733, 5734, 5735, 5736, 5737, 5738, 5739, 5740, 5741, 5742, 5743, 5744, 5745, 5746, 5747, 5748, 5749, 5750, 5751, 5752, 5753, 5754, 5755, 5756, 5757, 5758, 5759, 5760, 5761, 5762, 5763, 5764, 5765, 5766, 5767, 5768, 5769, 5770, 5771, 5772, 5773, 5774, 5775, 5776, 5777, 5778, 5779, 5780, 5781, 5782, 5783, 5784, 5785, 5786, 5787, 5788, 5789, 5790, 5791, 5792, 5793, 5794, 5795, 5796, 5797, 5798, 5799, 5800, 5801, 5802, 5803, 5804, 5805, 5806, 5807, 5808, 5809, 5810, 5811, 5812, 5813, 5814, 5815, 5816, 5817, 5818, 5819, 5820);
DELETE FROM room_waitlists WHERE room_id IN (5521, 5522, 5523, 5524, 5525, 5526, 5527, 5528, 5529, 5530, 5531, 5532, 5533, 5534, 5535, 5536, 5537, 5538, 5539, 5540, 5541, 5542, 5543, 5544, 5545, 5546, 5547, 5548, 5549, 5550, 5551, 5552, 5553, 5554, 5555, 5556, 5557, 5558, 5559, 5560, 5561, 5562, 5563, 5564, 5565, 5566, 5567, 5568, 5569, 5570, 5571, 5572, 5573, 5574, 5575, 5576, 5577, 5578, 5579, 5580, 5581, 5582, 5583, 5584, 5585, 5586, 5587, 5588, 5589, 5590, 5591, 5592, 5593, 5594, 5595, 5596, 5597, 5598, 5599, 5600, 5601, 5602, 5603, 5604, 5605, 5606, 5607, 5608, 5609, 5610, 5611, 5612, 5613, 5614, 5615, 5616, 5617, 5618, 5619, 5620, 5621, 5622, 5623, 5624, 5625, 5626, 5627, 5628, 5629, 5630, 5631, 5632, 5633, 5634, 5635, 5636, 5637, 5638, 5639, 5640, 5641, 5642, 5643, 5644, 5645, 5646, 5647, 5648, 5649, 5650, 5651, 5652, 5653, 5654, 5655, 5656, 5657, 5658, 5659, 5660, 5661, 5662, 5663, 5664, 5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680, 5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 5701, 5702, 5703, 5704, 5705, 5706, 5707, 5708, 5709, 5710, 5711, 5712, 5713, 5714, 5715, 5716, 5717, 5718, 5719, 5720, 5721, 5722, 5723, 5724, 5725, 5726, 5727, 5728, 5729, 5730, 5731, 5732, 5733, 5734, 5735, 5736, 5737, 5738, 5739, 5740, 5741, 5742, 5743, 5744, 5745, 5746, 5747, 5748, 5749, 5750, 5751, 5752, 5753, 5754, 5755, 5756, 5757, 5758, 5759, 5760, 5761, 5762, 5763, 5764, 5765, 5766, 5767, 5768, 5769, 5770, 5771, 5772, 5773, 5774, 5775, 5776, 5777, 5778, 5779, 5780, 5781, 5782, 5783, 5784, 5785, 5786, 5787, 5788, 5789, 5790, 5791, 5792, 5793, 5794, 5795, 5796, 5797, 5798, 5799, 5800, 5801, 5802, 5803, 5804, 5805, 5806, 5807, 5808, 5809, 5810, 5811, 5812, 5813, 5814, 5815, 5816, 5817, 5818, 5819, 5820);
DELETE FROM participations WHERE room_id IN (5521, 5522, 5523, 5524, 5525, 5526, 5527, 5528, 5529, 5530, 5531, 5532, 5533, 5534, 5535, 5536, 5537, 5538, 5539, 5540, 5541, 5542, 5543, 5544, 5545, 5546, 5547, 5548, 5549, 5550, 5551, 5552, 5553, 5554, 5555, 5556, 5557, 5558, 5559, 5560, 5561, 5562, 5563, 5564, 5565, 5566, 5567, 5568, 5569, 5570, 5571, 5572, 5573, 5574, 5575, 5576, 5577, 5578, 5579, 5580, 5581, 5582, 5583, 5584, 5585, 5586, 5587, 5588, 5589, 5590, 5591, 5592, 5593, 5594, 5595, 5596, 5597, 5598, 5599, 5600, 5601, 5602, 5603, 5604, 5605, 5606, 5607, 5608, 5609, 5610, 5611, 5612, 5613, 5614, 5615, 5616, 5617, 5618, 5619, 5620, 5621, 5622, 5623, 5624, 5625, 5626, 5627, 5628, 5629, 5630, 5631, 5632, 5633, 5634, 5635, 5636, 5637, 5638, 5639, 5640, 5641, 5642, 5643, 5644, 5645, 5646, 5647, 5648, 5649, 5650, 5651, 5652, 5653, 5654, 5655, 5656, 5657, 5658, 5659, 5660, 5661, 5662, 5663, 5664, 5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680, 5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 5701, 5702, 5703, 5704, 5705, 5706, 5707, 5708, 5709, 5710, 5711, 5712, 5713, 5714, 5715, 5716, 5717, 5718, 5719, 5720, 5721, 5722, 5723, 5724, 5725, 5726, 5727, 5728, 5729, 5730, 5731, 5732, 5733, 5734, 5735, 5736, 5737, 5738, 5739, 5740, 5741, 5742, 5743, 5744, 5745, 5746, 5747, 5748, 5749, 5750, 5751, 5752, 5753, 5754, 5755, 5756, 5757, 5758, 5759, 5760, 5761, 5762, 5763, 5764, 5765, 5766, 5767, 5768, 5769, 5770, 5771, 5772, 5773, 5774, 5775, 5776, 5777, 5778, 5779, 5780, 5781, 5782, 5783, 5784, 5785, 5786, 5787, 5788, 5789, 5790, 5791, 5792, 5793, 5794, 5795, 5796, 5797, 5798, 5799, 5800, 5801, 5802, 5803, 5804, 5805, 5806, 5807, 5808, 5809, 5810, 5811, 5812, 5813, 5814, 5815, 5816, 5817, 5818, 5819, 5820);
DELETE FROM rooms WHERE id IN (5521, 5522, 5523, 5524, 5525, 5526, 5527, 5528, 5529, 5530, 5531, 5532, 5533, 5534, 5535, 5536, 5537, 5538, 5539, 5540, 5541, 5542, 5543, 5544, 5545, 5546, 5547, 5548, 5549, 5550, 5551, 5552, 5553, 5554, 5555, 5556, 5557, 5558, 5559, 5560, 5561, 5562, 5563, 5564, 5565, 5566, 5567, 5568, 5569, 5570, 5571, 5572, 5573, 5574, 5575, 5576, 5577, 5578, 5579, 5580, 5581, 5582, 5583, 5584, 5585, 5586, 5587, 5588, 5589, 5590, 5591, 5592, 5593, 5594, 5595, 5596, 5597, 5598, 5599, 5600, 5601, 5602, 5603, 5604, 5605, 5606, 5607, 5608, 5609, 5610, 5611, 5612, 5613, 5614, 5615, 5616, 5617, 5618, 5619, 5620, 5621, 5622, 5623, 5624, 5625, 5626, 5627, 5628, 5629, 5630, 5631, 5632, 5633, 5634, 5635, 5636, 5637, 5638, 5639, 5640, 5641, 5642, 5643, 5644, 5645, 5646, 5647, 5648, 5649, 5650, 5651, 5652, 5653, 5654, 5655, 5656, 5657, 5658, 5659, 5660, 5661, 5662, 5663, 5664, 5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680, 5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 5701, 5702, 5703, 5704, 5705, 5706, 5707, 5708, 5709, 5710, 5711, 5712, 5713, 5714, 5715, 5716, 5717, 5718, 5719, 5720, 5721, 5722, 5723, 5724, 5725, 5726, 5727, 5728, 5729, 5730, 5731, 5732, 5733, 5734, 5735, 5736, 5737, 5738, 5739, 5740, 5741, 5742, 5743, 5744, 5745, 5746, 5747, 5748, 5749, 5750, 5751, 5752, 5753, 5754, 5755, 5756, 5757, 5758, 5759, 5760, 5761, 5762, 5763, 5764, 5765, 5766, 5767, 5768, 5769, 5770, 5771, 5772, 5773, 5774, 5775, 5776, 5777, 5778, 5779, 5780, 5781, 5782, 5783, 5784, 5785, 5786, 5787, 5788, 5789, 5790, 5791, 5792, 5793, 5794, 5795, 5796, 5797, 5798, 5799, 5800, 5801, 5802, 5803, 5804, 5805, 5806, 5807, 5808, 5809, 5810, 5811, 5812, 5813, 5814, 5815, 5816, 5817, 5818, 5819, 5820);
DELETE FROM users WHERE id IN (1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024, 1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034, 1035, 1036, 1037);

COMMIT;
