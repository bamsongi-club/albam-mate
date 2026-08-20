\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-06d831bd0278'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (934, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-host@example.invalid'),
    (935, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-cancel-0@example.invalid'),
    (936, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-cancel-1@example.invalid'),
    (937, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-cancel-2@example.invalid'),
    (938, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-cancel-3@example.invalid'),
    (939, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-waiter-0@example.invalid'),
    (940, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-waiter-1@example.invalid'),
    (941, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-waiter-2@example.invalid'),
    (942, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-waiter-3@example.invalid'),
    (943, 'room-k6.room-k6-t1-06d831bd0278.t1-mixed-waiter-4@example.invalid'),
    (944, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s4-host@example.invalid'),
    (945, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s4-cancel@example.invalid'),
    (946, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s4-waiter-0@example.invalid'),
    (947, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s4-waiter-1@example.invalid'),
    (948, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s5-host@example.invalid'),
    (949, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s5-cancel@example.invalid'),
    (950, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s5-waiter-0@example.invalid'),
    (951, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s5-waiter-1@example.invalid'),
    (952, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s6-host@example.invalid'),
    (953, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s6-cancel@example.invalid'),
    (954, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s6-waiter-0@example.invalid'),
    (955, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s6-waiter-1@example.invalid'),
    (956, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s7-host@example.invalid'),
    (957, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s7-cancel@example.invalid'),
    (958, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s7-waiter-0@example.invalid'),
    (959, 'room-k6.room-k6-t1-06d831bd0278.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (4621, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r0-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4622, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r0-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4623, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r0-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4624, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r0-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4625, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r0-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4626, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r1-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4627, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r1-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4628, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r1-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4629, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r1-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4630, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r1-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4631, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r2-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4632, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r2-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4633, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r2-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4634, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r2-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4635, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r2-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4636, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r3-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4637, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r3-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4638, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r3-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4639, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r3-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4640, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r3-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4641, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r4-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4642, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r4-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4643, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r4-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4644, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r4-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4645, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r4-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4646, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r5-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4647, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r5-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4648, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r5-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4649, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r5-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4650, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r5-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4651, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r6-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4652, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r6-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4653, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r6-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4654, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r6-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4655, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r6-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4656, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r7-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4657, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r7-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4658, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r7-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4659, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r7-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4660, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r7-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4661, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r8-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4662, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r8-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4663, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r8-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4664, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r8-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4665, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r8-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4666, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r9-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4667, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r9-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4668, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r9-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4669, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r9-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4670, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r9-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4671, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r10-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4672, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r10-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4673, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r10-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4674, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r10-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4675, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r10-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4676, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r11-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4677, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r11-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4678, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r11-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4679, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r11-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4680, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r11-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4681, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r12-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4682, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r12-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4683, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r12-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4684, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r12-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4685, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r12-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4686, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r13-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4687, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r13-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4688, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r13-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4689, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r13-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4690, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r13-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4691, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r14-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4692, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r14-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4693, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r14-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4694, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r14-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4695, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r14-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4696, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r15-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4697, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r15-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4698, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r15-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4699, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r15-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4700, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r15-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4701, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r16-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4702, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r16-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4703, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r16-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4704, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r16-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4705, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r16-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4706, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r17-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4707, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r17-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4708, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r17-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4709, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r17-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4710, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r17-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4711, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r18-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4712, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r18-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4713, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r18-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4714, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r18-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4715, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r18-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4716, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r19-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4717, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r19-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4718, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r19-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4719, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r19-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4720, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r19-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4721, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r20-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4722, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r20-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4723, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r20-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4724, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r20-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4725, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r20-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4726, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r21-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4727, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r21-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4728, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r21-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4729, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r21-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4730, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r21-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4731, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r22-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4732, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r22-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4733, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r22-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4734, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r22-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4735, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r22-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4736, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r23-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4737, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r23-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4738, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r23-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4739, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r23-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4740, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r23-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4741, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r24-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4742, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r24-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4743, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r24-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4744, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r24-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4745, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r24-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4746, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r25-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4747, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r25-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4748, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r25-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4749, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r25-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4750, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r25-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4751, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r26-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4752, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r26-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4753, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r26-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4754, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r26-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4755, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r26-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4756, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r27-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4757, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r27-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4758, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r27-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4759, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r27-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4760, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r27-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4761, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r28-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4762, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r28-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4763, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r28-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4764, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r28-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4765, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r28-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4766, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r29-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4767, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r29-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4768, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r29-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4769, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r29-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4770, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r29-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4771, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r30-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4772, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r30-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4773, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r30-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4774, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r30-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4775, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r30-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4776, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r31-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4777, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r31-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4778, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r31-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4779, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r31-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4780, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r31-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4781, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r32-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4782, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r32-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4783, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r32-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4784, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r32-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4785, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r32-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4786, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r33-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4787, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r33-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4788, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r33-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4789, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r33-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4790, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r33-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4791, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r34-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4792, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r34-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4793, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r34-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4794, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r34-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4795, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r34-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4796, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r35-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4797, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r35-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4798, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r35-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4799, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r35-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4800, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r35-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4801, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r36-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4802, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r36-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4803, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r36-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4804, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r36-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4805, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r36-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4806, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r37-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4807, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r37-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4808, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r37-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4809, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r37-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4810, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r37-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4811, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r38-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4812, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r38-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4813, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r38-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4814, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r38-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4815, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r38-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4816, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r39-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4817, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r39-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4818, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r39-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4819, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r39-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4820, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r39-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4821, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r40-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4822, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r40-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4823, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r40-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4824, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r40-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4825, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r40-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4826, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r41-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4827, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r41-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4828, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r41-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4829, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r41-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4830, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r41-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4831, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r42-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4832, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r42-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4833, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r42-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4834, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r42-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4835, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r42-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4836, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r43-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4837, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r43-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4838, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r43-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4839, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r43-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4840, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r43-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4841, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r44-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4842, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r44-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4843, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r44-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4844, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r44-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4845, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r44-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4846, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r45-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4847, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r45-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4848, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r45-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4849, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r45-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4850, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r45-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4851, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r46-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4852, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r46-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4853, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r46-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4854, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r46-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4855, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r46-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4856, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r47-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4857, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r47-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4858, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r47-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4859, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r47-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4860, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r47-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4861, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r48-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4862, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r48-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4863, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r48-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4864, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r48-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4865, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r48-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4866, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r49-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4867, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r49-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4868, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r49-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4869, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r49-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4870, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r49-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4871, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r50-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4872, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r50-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4873, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r50-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4874, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r50-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4875, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r50-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4876, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r51-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4877, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r51-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4878, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r51-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4879, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r51-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4880, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r51-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4881, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r52-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4882, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r52-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4883, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r52-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4884, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r52-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4885, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r52-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4886, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r53-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4887, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r53-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4888, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r53-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4889, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r53-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4890, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r53-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4891, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r54-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4892, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r54-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4893, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r54-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4894, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r54-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4895, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r54-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4896, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r55-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4897, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r55-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4898, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r55-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4899, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r55-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4900, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r55-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4901, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r56-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4902, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r56-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4903, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r56-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4904, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r56-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4905, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r56-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4906, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r57-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4907, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r57-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4908, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r57-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4909, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r57-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4910, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r57-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4911, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r58-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4912, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r58-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4913, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r58-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4914, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r58-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4915, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r58-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4916, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r59-mixed-hot', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4917, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r59-spread-s4', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4918, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r59-spread-s5', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4919, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r59-spread-s6', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69'),
    (4920, 'ROOM-K6 room-k6-t1-06d831bd0278 t1-r59-spread-s7', 'ROOM k6 fixture c831bace4f4a4fc79768cb9d030fbe69');

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

DELETE FROM notifications WHERE room_id IN (4621, 4622, 4623, 4624, 4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 4640, 4641, 4642, 4643, 4644, 4645, 4646, 4647, 4648, 4649, 4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657, 4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665, 4666, 4667, 4668, 4669, 4670, 4671, 4672, 4673, 4674, 4675, 4676, 4677, 4678, 4679, 4680, 4681, 4682, 4683, 4684, 4685, 4686, 4687, 4688, 4689, 4690, 4691, 4692, 4693, 4694, 4695, 4696, 4697, 4698, 4699, 4700, 4701, 4702, 4703, 4704, 4705, 4706, 4707, 4708, 4709, 4710, 4711, 4712, 4713, 4714, 4715, 4716, 4717, 4718, 4719, 4720, 4721, 4722, 4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739, 4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759, 4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781, 4782, 4783, 4784, 4785, 4786, 4787, 4788, 4789, 4790, 4791, 4792, 4793, 4794, 4795, 4796, 4797, 4798, 4799, 4800, 4801, 4802, 4803, 4804, 4805, 4806, 4807, 4808, 4809, 4810, 4811, 4812, 4813, 4814, 4815, 4816, 4817, 4818, 4819, 4820, 4821, 4822, 4823, 4824, 4825, 4826, 4827, 4828, 4829, 4830, 4831, 4832, 4833, 4834, 4835, 4836, 4837, 4838, 4839, 4840, 4841, 4842, 4843, 4844, 4845, 4846, 4847, 4848, 4849, 4850, 4851, 4852, 4853, 4854, 4855, 4856, 4857, 4858, 4859, 4860, 4861, 4862, 4863, 4864, 4865, 4866, 4867, 4868, 4869, 4870, 4871, 4872, 4873, 4874, 4875, 4876, 4877, 4878, 4879, 4880, 4881, 4882, 4883, 4884, 4885, 4886, 4887, 4888, 4889, 4890, 4891, 4892, 4893, 4894, 4895, 4896, 4897, 4898, 4899, 4900, 4901, 4902, 4903, 4904, 4905, 4906, 4907, 4908, 4909, 4910, 4911, 4912, 4913, 4914, 4915, 4916, 4917, 4918, 4919, 4920);
DELETE FROM notification_outbox_events WHERE room_id IN (4621, 4622, 4623, 4624, 4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 4640, 4641, 4642, 4643, 4644, 4645, 4646, 4647, 4648, 4649, 4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657, 4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665, 4666, 4667, 4668, 4669, 4670, 4671, 4672, 4673, 4674, 4675, 4676, 4677, 4678, 4679, 4680, 4681, 4682, 4683, 4684, 4685, 4686, 4687, 4688, 4689, 4690, 4691, 4692, 4693, 4694, 4695, 4696, 4697, 4698, 4699, 4700, 4701, 4702, 4703, 4704, 4705, 4706, 4707, 4708, 4709, 4710, 4711, 4712, 4713, 4714, 4715, 4716, 4717, 4718, 4719, 4720, 4721, 4722, 4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739, 4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759, 4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781, 4782, 4783, 4784, 4785, 4786, 4787, 4788, 4789, 4790, 4791, 4792, 4793, 4794, 4795, 4796, 4797, 4798, 4799, 4800, 4801, 4802, 4803, 4804, 4805, 4806, 4807, 4808, 4809, 4810, 4811, 4812, 4813, 4814, 4815, 4816, 4817, 4818, 4819, 4820, 4821, 4822, 4823, 4824, 4825, 4826, 4827, 4828, 4829, 4830, 4831, 4832, 4833, 4834, 4835, 4836, 4837, 4838, 4839, 4840, 4841, 4842, 4843, 4844, 4845, 4846, 4847, 4848, 4849, 4850, 4851, 4852, 4853, 4854, 4855, 4856, 4857, 4858, 4859, 4860, 4861, 4862, 4863, 4864, 4865, 4866, 4867, 4868, 4869, 4870, 4871, 4872, 4873, 4874, 4875, 4876, 4877, 4878, 4879, 4880, 4881, 4882, 4883, 4884, 4885, 4886, 4887, 4888, 4889, 4890, 4891, 4892, 4893, 4894, 4895, 4896, 4897, 4898, 4899, 4900, 4901, 4902, 4903, 4904, 4905, 4906, 4907, 4908, 4909, 4910, 4911, 4912, 4913, 4914, 4915, 4916, 4917, 4918, 4919, 4920);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (4621, 4622, 4623, 4624, 4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 4640, 4641, 4642, 4643, 4644, 4645, 4646, 4647, 4648, 4649, 4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657, 4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665, 4666, 4667, 4668, 4669, 4670, 4671, 4672, 4673, 4674, 4675, 4676, 4677, 4678, 4679, 4680, 4681, 4682, 4683, 4684, 4685, 4686, 4687, 4688, 4689, 4690, 4691, 4692, 4693, 4694, 4695, 4696, 4697, 4698, 4699, 4700, 4701, 4702, 4703, 4704, 4705, 4706, 4707, 4708, 4709, 4710, 4711, 4712, 4713, 4714, 4715, 4716, 4717, 4718, 4719, 4720, 4721, 4722, 4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739, 4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759, 4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781, 4782, 4783, 4784, 4785, 4786, 4787, 4788, 4789, 4790, 4791, 4792, 4793, 4794, 4795, 4796, 4797, 4798, 4799, 4800, 4801, 4802, 4803, 4804, 4805, 4806, 4807, 4808, 4809, 4810, 4811, 4812, 4813, 4814, 4815, 4816, 4817, 4818, 4819, 4820, 4821, 4822, 4823, 4824, 4825, 4826, 4827, 4828, 4829, 4830, 4831, 4832, 4833, 4834, 4835, 4836, 4837, 4838, 4839, 4840, 4841, 4842, 4843, 4844, 4845, 4846, 4847, 4848, 4849, 4850, 4851, 4852, 4853, 4854, 4855, 4856, 4857, 4858, 4859, 4860, 4861, 4862, 4863, 4864, 4865, 4866, 4867, 4868, 4869, 4870, 4871, 4872, 4873, 4874, 4875, 4876, 4877, 4878, 4879, 4880, 4881, 4882, 4883, 4884, 4885, 4886, 4887, 4888, 4889, 4890, 4891, 4892, 4893, 4894, 4895, 4896, 4897, 4898, 4899, 4900, 4901, 4902, 4903, 4904, 4905, 4906, 4907, 4908, 4909, 4910, 4911, 4912, 4913, 4914, 4915, 4916, 4917, 4918, 4919, 4920)
);
DELETE FROM chat_rooms WHERE room_id IN (4621, 4622, 4623, 4624, 4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 4640, 4641, 4642, 4643, 4644, 4645, 4646, 4647, 4648, 4649, 4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657, 4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665, 4666, 4667, 4668, 4669, 4670, 4671, 4672, 4673, 4674, 4675, 4676, 4677, 4678, 4679, 4680, 4681, 4682, 4683, 4684, 4685, 4686, 4687, 4688, 4689, 4690, 4691, 4692, 4693, 4694, 4695, 4696, 4697, 4698, 4699, 4700, 4701, 4702, 4703, 4704, 4705, 4706, 4707, 4708, 4709, 4710, 4711, 4712, 4713, 4714, 4715, 4716, 4717, 4718, 4719, 4720, 4721, 4722, 4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739, 4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759, 4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781, 4782, 4783, 4784, 4785, 4786, 4787, 4788, 4789, 4790, 4791, 4792, 4793, 4794, 4795, 4796, 4797, 4798, 4799, 4800, 4801, 4802, 4803, 4804, 4805, 4806, 4807, 4808, 4809, 4810, 4811, 4812, 4813, 4814, 4815, 4816, 4817, 4818, 4819, 4820, 4821, 4822, 4823, 4824, 4825, 4826, 4827, 4828, 4829, 4830, 4831, 4832, 4833, 4834, 4835, 4836, 4837, 4838, 4839, 4840, 4841, 4842, 4843, 4844, 4845, 4846, 4847, 4848, 4849, 4850, 4851, 4852, 4853, 4854, 4855, 4856, 4857, 4858, 4859, 4860, 4861, 4862, 4863, 4864, 4865, 4866, 4867, 4868, 4869, 4870, 4871, 4872, 4873, 4874, 4875, 4876, 4877, 4878, 4879, 4880, 4881, 4882, 4883, 4884, 4885, 4886, 4887, 4888, 4889, 4890, 4891, 4892, 4893, 4894, 4895, 4896, 4897, 4898, 4899, 4900, 4901, 4902, 4903, 4904, 4905, 4906, 4907, 4908, 4909, 4910, 4911, 4912, 4913, 4914, 4915, 4916, 4917, 4918, 4919, 4920);
DELETE FROM room_waitlists WHERE room_id IN (4621, 4622, 4623, 4624, 4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 4640, 4641, 4642, 4643, 4644, 4645, 4646, 4647, 4648, 4649, 4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657, 4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665, 4666, 4667, 4668, 4669, 4670, 4671, 4672, 4673, 4674, 4675, 4676, 4677, 4678, 4679, 4680, 4681, 4682, 4683, 4684, 4685, 4686, 4687, 4688, 4689, 4690, 4691, 4692, 4693, 4694, 4695, 4696, 4697, 4698, 4699, 4700, 4701, 4702, 4703, 4704, 4705, 4706, 4707, 4708, 4709, 4710, 4711, 4712, 4713, 4714, 4715, 4716, 4717, 4718, 4719, 4720, 4721, 4722, 4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739, 4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759, 4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781, 4782, 4783, 4784, 4785, 4786, 4787, 4788, 4789, 4790, 4791, 4792, 4793, 4794, 4795, 4796, 4797, 4798, 4799, 4800, 4801, 4802, 4803, 4804, 4805, 4806, 4807, 4808, 4809, 4810, 4811, 4812, 4813, 4814, 4815, 4816, 4817, 4818, 4819, 4820, 4821, 4822, 4823, 4824, 4825, 4826, 4827, 4828, 4829, 4830, 4831, 4832, 4833, 4834, 4835, 4836, 4837, 4838, 4839, 4840, 4841, 4842, 4843, 4844, 4845, 4846, 4847, 4848, 4849, 4850, 4851, 4852, 4853, 4854, 4855, 4856, 4857, 4858, 4859, 4860, 4861, 4862, 4863, 4864, 4865, 4866, 4867, 4868, 4869, 4870, 4871, 4872, 4873, 4874, 4875, 4876, 4877, 4878, 4879, 4880, 4881, 4882, 4883, 4884, 4885, 4886, 4887, 4888, 4889, 4890, 4891, 4892, 4893, 4894, 4895, 4896, 4897, 4898, 4899, 4900, 4901, 4902, 4903, 4904, 4905, 4906, 4907, 4908, 4909, 4910, 4911, 4912, 4913, 4914, 4915, 4916, 4917, 4918, 4919, 4920);
DELETE FROM participations WHERE room_id IN (4621, 4622, 4623, 4624, 4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 4640, 4641, 4642, 4643, 4644, 4645, 4646, 4647, 4648, 4649, 4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657, 4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665, 4666, 4667, 4668, 4669, 4670, 4671, 4672, 4673, 4674, 4675, 4676, 4677, 4678, 4679, 4680, 4681, 4682, 4683, 4684, 4685, 4686, 4687, 4688, 4689, 4690, 4691, 4692, 4693, 4694, 4695, 4696, 4697, 4698, 4699, 4700, 4701, 4702, 4703, 4704, 4705, 4706, 4707, 4708, 4709, 4710, 4711, 4712, 4713, 4714, 4715, 4716, 4717, 4718, 4719, 4720, 4721, 4722, 4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739, 4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759, 4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781, 4782, 4783, 4784, 4785, 4786, 4787, 4788, 4789, 4790, 4791, 4792, 4793, 4794, 4795, 4796, 4797, 4798, 4799, 4800, 4801, 4802, 4803, 4804, 4805, 4806, 4807, 4808, 4809, 4810, 4811, 4812, 4813, 4814, 4815, 4816, 4817, 4818, 4819, 4820, 4821, 4822, 4823, 4824, 4825, 4826, 4827, 4828, 4829, 4830, 4831, 4832, 4833, 4834, 4835, 4836, 4837, 4838, 4839, 4840, 4841, 4842, 4843, 4844, 4845, 4846, 4847, 4848, 4849, 4850, 4851, 4852, 4853, 4854, 4855, 4856, 4857, 4858, 4859, 4860, 4861, 4862, 4863, 4864, 4865, 4866, 4867, 4868, 4869, 4870, 4871, 4872, 4873, 4874, 4875, 4876, 4877, 4878, 4879, 4880, 4881, 4882, 4883, 4884, 4885, 4886, 4887, 4888, 4889, 4890, 4891, 4892, 4893, 4894, 4895, 4896, 4897, 4898, 4899, 4900, 4901, 4902, 4903, 4904, 4905, 4906, 4907, 4908, 4909, 4910, 4911, 4912, 4913, 4914, 4915, 4916, 4917, 4918, 4919, 4920);
DELETE FROM rooms WHERE id IN (4621, 4622, 4623, 4624, 4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 4640, 4641, 4642, 4643, 4644, 4645, 4646, 4647, 4648, 4649, 4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657, 4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665, 4666, 4667, 4668, 4669, 4670, 4671, 4672, 4673, 4674, 4675, 4676, 4677, 4678, 4679, 4680, 4681, 4682, 4683, 4684, 4685, 4686, 4687, 4688, 4689, 4690, 4691, 4692, 4693, 4694, 4695, 4696, 4697, 4698, 4699, 4700, 4701, 4702, 4703, 4704, 4705, 4706, 4707, 4708, 4709, 4710, 4711, 4712, 4713, 4714, 4715, 4716, 4717, 4718, 4719, 4720, 4721, 4722, 4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739, 4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759, 4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781, 4782, 4783, 4784, 4785, 4786, 4787, 4788, 4789, 4790, 4791, 4792, 4793, 4794, 4795, 4796, 4797, 4798, 4799, 4800, 4801, 4802, 4803, 4804, 4805, 4806, 4807, 4808, 4809, 4810, 4811, 4812, 4813, 4814, 4815, 4816, 4817, 4818, 4819, 4820, 4821, 4822, 4823, 4824, 4825, 4826, 4827, 4828, 4829, 4830, 4831, 4832, 4833, 4834, 4835, 4836, 4837, 4838, 4839, 4840, 4841, 4842, 4843, 4844, 4845, 4846, 4847, 4848, 4849, 4850, 4851, 4852, 4853, 4854, 4855, 4856, 4857, 4858, 4859, 4860, 4861, 4862, 4863, 4864, 4865, 4866, 4867, 4868, 4869, 4870, 4871, 4872, 4873, 4874, 4875, 4876, 4877, 4878, 4879, 4880, 4881, 4882, 4883, 4884, 4885, 4886, 4887, 4888, 4889, 4890, 4891, 4892, 4893, 4894, 4895, 4896, 4897, 4898, 4899, 4900, 4901, 4902, 4903, 4904, 4905, 4906, 4907, 4908, 4909, 4910, 4911, 4912, 4913, 4914, 4915, 4916, 4917, 4918, 4919, 4920);
DELETE FROM users WHERE id IN (934, 935, 936, 937, 938, 939, 940, 941, 942, 943, 944, 945, 946, 947, 948, 949, 950, 951, 952, 953, 954, 955, 956, 957, 958, 959);

COMMIT;
