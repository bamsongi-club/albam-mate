\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-37ff81b803cd'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (960, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-host@example.invalid'),
    (961, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-cancel-0@example.invalid'),
    (962, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-cancel-1@example.invalid'),
    (963, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-cancel-2@example.invalid'),
    (964, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-cancel-3@example.invalid'),
    (965, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-waiter-0@example.invalid'),
    (966, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-waiter-1@example.invalid'),
    (967, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-waiter-2@example.invalid'),
    (968, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-waiter-3@example.invalid'),
    (969, 'room-k6.room-k6-t1-37ff81b803cd.t1-mixed-waiter-4@example.invalid'),
    (970, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s4-host@example.invalid'),
    (971, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s4-cancel@example.invalid'),
    (972, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s4-waiter-0@example.invalid'),
    (973, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s4-waiter-1@example.invalid'),
    (974, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s5-host@example.invalid'),
    (975, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s5-cancel@example.invalid'),
    (976, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s5-waiter-0@example.invalid'),
    (977, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s5-waiter-1@example.invalid'),
    (978, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s6-host@example.invalid'),
    (979, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s6-cancel@example.invalid'),
    (980, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s6-waiter-0@example.invalid'),
    (981, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s6-waiter-1@example.invalid'),
    (982, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s7-host@example.invalid'),
    (983, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s7-cancel@example.invalid'),
    (984, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s7-waiter-0@example.invalid'),
    (985, 'room-k6.room-k6-t1-37ff81b803cd.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (4921, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r0-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4922, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r0-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4923, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r0-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4924, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r0-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4925, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r0-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4926, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r1-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4927, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r1-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4928, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r1-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4929, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r1-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4930, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r1-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4931, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r2-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4932, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r2-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4933, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r2-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4934, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r2-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4935, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r2-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4936, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r3-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4937, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r3-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4938, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r3-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4939, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r3-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4940, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r3-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4941, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r4-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4942, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r4-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4943, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r4-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4944, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r4-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4945, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r4-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4946, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r5-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4947, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r5-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4948, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r5-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4949, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r5-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4950, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r5-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4951, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r6-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4952, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r6-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4953, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r6-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4954, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r6-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4955, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r6-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4956, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r7-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4957, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r7-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4958, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r7-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4959, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r7-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4960, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r7-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4961, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r8-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4962, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r8-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4963, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r8-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4964, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r8-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4965, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r8-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4966, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r9-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4967, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r9-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4968, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r9-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4969, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r9-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4970, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r9-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4971, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r10-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4972, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r10-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4973, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r10-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4974, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r10-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4975, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r10-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4976, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r11-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4977, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r11-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4978, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r11-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4979, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r11-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4980, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r11-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4981, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r12-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4982, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r12-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4983, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r12-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4984, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r12-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4985, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r12-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4986, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r13-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4987, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r13-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4988, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r13-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4989, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r13-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4990, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r13-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4991, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r14-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4992, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r14-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4993, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r14-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4994, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r14-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4995, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r14-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4996, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r15-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4997, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r15-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4998, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r15-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (4999, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r15-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5000, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r15-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5001, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r16-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5002, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r16-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5003, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r16-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5004, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r16-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5005, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r16-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5006, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r17-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5007, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r17-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5008, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r17-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5009, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r17-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5010, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r17-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5011, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r18-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5012, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r18-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5013, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r18-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5014, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r18-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5015, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r18-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5016, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r19-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5017, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r19-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5018, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r19-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5019, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r19-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5020, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r19-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5021, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r20-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5022, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r20-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5023, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r20-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5024, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r20-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5025, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r20-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5026, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r21-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5027, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r21-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5028, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r21-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5029, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r21-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5030, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r21-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5031, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r22-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5032, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r22-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5033, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r22-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5034, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r22-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5035, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r22-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5036, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r23-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5037, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r23-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5038, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r23-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5039, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r23-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5040, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r23-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5041, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r24-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5042, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r24-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5043, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r24-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5044, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r24-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5045, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r24-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5046, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r25-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5047, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r25-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5048, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r25-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5049, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r25-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5050, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r25-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5051, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r26-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5052, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r26-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5053, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r26-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5054, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r26-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5055, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r26-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5056, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r27-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5057, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r27-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5058, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r27-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5059, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r27-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5060, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r27-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5061, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r28-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5062, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r28-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5063, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r28-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5064, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r28-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5065, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r28-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5066, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r29-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5067, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r29-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5068, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r29-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5069, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r29-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5070, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r29-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5071, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r30-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5072, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r30-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5073, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r30-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5074, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r30-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5075, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r30-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5076, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r31-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5077, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r31-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5078, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r31-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5079, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r31-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5080, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r31-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5081, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r32-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5082, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r32-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5083, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r32-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5084, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r32-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5085, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r32-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5086, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r33-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5087, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r33-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5088, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r33-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5089, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r33-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5090, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r33-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5091, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r34-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5092, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r34-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5093, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r34-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5094, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r34-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5095, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r34-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5096, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r35-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5097, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r35-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5098, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r35-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5099, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r35-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5100, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r35-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5101, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r36-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5102, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r36-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5103, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r36-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5104, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r36-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5105, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r36-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5106, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r37-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5107, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r37-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5108, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r37-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5109, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r37-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5110, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r37-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5111, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r38-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5112, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r38-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5113, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r38-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5114, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r38-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5115, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r38-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5116, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r39-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5117, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r39-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5118, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r39-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5119, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r39-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5120, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r39-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5121, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r40-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5122, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r40-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5123, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r40-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5124, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r40-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5125, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r40-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5126, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r41-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5127, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r41-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5128, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r41-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5129, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r41-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5130, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r41-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5131, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r42-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5132, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r42-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5133, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r42-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5134, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r42-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5135, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r42-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5136, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r43-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5137, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r43-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5138, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r43-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5139, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r43-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5140, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r43-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5141, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r44-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5142, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r44-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5143, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r44-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5144, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r44-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5145, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r44-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5146, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r45-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5147, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r45-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5148, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r45-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5149, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r45-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5150, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r45-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5151, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r46-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5152, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r46-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5153, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r46-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5154, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r46-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5155, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r46-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5156, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r47-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5157, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r47-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5158, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r47-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5159, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r47-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5160, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r47-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5161, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r48-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5162, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r48-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5163, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r48-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5164, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r48-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5165, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r48-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5166, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r49-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5167, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r49-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5168, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r49-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5169, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r49-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5170, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r49-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5171, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r50-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5172, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r50-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5173, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r50-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5174, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r50-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5175, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r50-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5176, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r51-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5177, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r51-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5178, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r51-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5179, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r51-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5180, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r51-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5181, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r52-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5182, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r52-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5183, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r52-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5184, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r52-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5185, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r52-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5186, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r53-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5187, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r53-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5188, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r53-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5189, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r53-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5190, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r53-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5191, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r54-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5192, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r54-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5193, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r54-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5194, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r54-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5195, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r54-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5196, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r55-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5197, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r55-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5198, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r55-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5199, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r55-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5200, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r55-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5201, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r56-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5202, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r56-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5203, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r56-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5204, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r56-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5205, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r56-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5206, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r57-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5207, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r57-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5208, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r57-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5209, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r57-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5210, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r57-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5211, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r58-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5212, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r58-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5213, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r58-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5214, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r58-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5215, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r58-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5216, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r59-mixed-hot', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5217, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r59-spread-s4', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5218, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r59-spread-s5', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5219, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r59-spread-s6', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829'),
    (5220, 'ROOM-K6 room-k6-t1-37ff81b803cd t1-r59-spread-s7', 'ROOM k6 fixture e59692cd22ec4317884c447d878b4829');

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

DELETE FROM notifications WHERE room_id IN (4921, 4922, 4923, 4924, 4925, 4926, 4927, 4928, 4929, 4930, 4931, 4932, 4933, 4934, 4935, 4936, 4937, 4938, 4939, 4940, 4941, 4942, 4943, 4944, 4945, 4946, 4947, 4948, 4949, 4950, 4951, 4952, 4953, 4954, 4955, 4956, 4957, 4958, 4959, 4960, 4961, 4962, 4963, 4964, 4965, 4966, 4967, 4968, 4969, 4970, 4971, 4972, 4973, 4974, 4975, 4976, 4977, 4978, 4979, 4980, 4981, 4982, 4983, 4984, 4985, 4986, 4987, 4988, 4989, 4990, 4991, 4992, 4993, 4994, 4995, 4996, 4997, 4998, 4999, 5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050, 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, 5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 5159, 5160, 5161, 5162, 5163, 5164, 5165, 5166, 5167, 5168, 5169, 5170, 5171, 5172, 5173, 5174, 5175, 5176, 5177, 5178, 5179, 5180, 5181, 5182, 5183, 5184, 5185, 5186, 5187, 5188, 5189, 5190, 5191, 5192, 5193, 5194, 5195, 5196, 5197, 5198, 5199, 5200, 5201, 5202, 5203, 5204, 5205, 5206, 5207, 5208, 5209, 5210, 5211, 5212, 5213, 5214, 5215, 5216, 5217, 5218, 5219, 5220);
DELETE FROM notification_outbox_events WHERE room_id IN (4921, 4922, 4923, 4924, 4925, 4926, 4927, 4928, 4929, 4930, 4931, 4932, 4933, 4934, 4935, 4936, 4937, 4938, 4939, 4940, 4941, 4942, 4943, 4944, 4945, 4946, 4947, 4948, 4949, 4950, 4951, 4952, 4953, 4954, 4955, 4956, 4957, 4958, 4959, 4960, 4961, 4962, 4963, 4964, 4965, 4966, 4967, 4968, 4969, 4970, 4971, 4972, 4973, 4974, 4975, 4976, 4977, 4978, 4979, 4980, 4981, 4982, 4983, 4984, 4985, 4986, 4987, 4988, 4989, 4990, 4991, 4992, 4993, 4994, 4995, 4996, 4997, 4998, 4999, 5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050, 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, 5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 5159, 5160, 5161, 5162, 5163, 5164, 5165, 5166, 5167, 5168, 5169, 5170, 5171, 5172, 5173, 5174, 5175, 5176, 5177, 5178, 5179, 5180, 5181, 5182, 5183, 5184, 5185, 5186, 5187, 5188, 5189, 5190, 5191, 5192, 5193, 5194, 5195, 5196, 5197, 5198, 5199, 5200, 5201, 5202, 5203, 5204, 5205, 5206, 5207, 5208, 5209, 5210, 5211, 5212, 5213, 5214, 5215, 5216, 5217, 5218, 5219, 5220);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (4921, 4922, 4923, 4924, 4925, 4926, 4927, 4928, 4929, 4930, 4931, 4932, 4933, 4934, 4935, 4936, 4937, 4938, 4939, 4940, 4941, 4942, 4943, 4944, 4945, 4946, 4947, 4948, 4949, 4950, 4951, 4952, 4953, 4954, 4955, 4956, 4957, 4958, 4959, 4960, 4961, 4962, 4963, 4964, 4965, 4966, 4967, 4968, 4969, 4970, 4971, 4972, 4973, 4974, 4975, 4976, 4977, 4978, 4979, 4980, 4981, 4982, 4983, 4984, 4985, 4986, 4987, 4988, 4989, 4990, 4991, 4992, 4993, 4994, 4995, 4996, 4997, 4998, 4999, 5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050, 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, 5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 5159, 5160, 5161, 5162, 5163, 5164, 5165, 5166, 5167, 5168, 5169, 5170, 5171, 5172, 5173, 5174, 5175, 5176, 5177, 5178, 5179, 5180, 5181, 5182, 5183, 5184, 5185, 5186, 5187, 5188, 5189, 5190, 5191, 5192, 5193, 5194, 5195, 5196, 5197, 5198, 5199, 5200, 5201, 5202, 5203, 5204, 5205, 5206, 5207, 5208, 5209, 5210, 5211, 5212, 5213, 5214, 5215, 5216, 5217, 5218, 5219, 5220)
);
DELETE FROM chat_rooms WHERE room_id IN (4921, 4922, 4923, 4924, 4925, 4926, 4927, 4928, 4929, 4930, 4931, 4932, 4933, 4934, 4935, 4936, 4937, 4938, 4939, 4940, 4941, 4942, 4943, 4944, 4945, 4946, 4947, 4948, 4949, 4950, 4951, 4952, 4953, 4954, 4955, 4956, 4957, 4958, 4959, 4960, 4961, 4962, 4963, 4964, 4965, 4966, 4967, 4968, 4969, 4970, 4971, 4972, 4973, 4974, 4975, 4976, 4977, 4978, 4979, 4980, 4981, 4982, 4983, 4984, 4985, 4986, 4987, 4988, 4989, 4990, 4991, 4992, 4993, 4994, 4995, 4996, 4997, 4998, 4999, 5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050, 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, 5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 5159, 5160, 5161, 5162, 5163, 5164, 5165, 5166, 5167, 5168, 5169, 5170, 5171, 5172, 5173, 5174, 5175, 5176, 5177, 5178, 5179, 5180, 5181, 5182, 5183, 5184, 5185, 5186, 5187, 5188, 5189, 5190, 5191, 5192, 5193, 5194, 5195, 5196, 5197, 5198, 5199, 5200, 5201, 5202, 5203, 5204, 5205, 5206, 5207, 5208, 5209, 5210, 5211, 5212, 5213, 5214, 5215, 5216, 5217, 5218, 5219, 5220);
DELETE FROM room_waitlists WHERE room_id IN (4921, 4922, 4923, 4924, 4925, 4926, 4927, 4928, 4929, 4930, 4931, 4932, 4933, 4934, 4935, 4936, 4937, 4938, 4939, 4940, 4941, 4942, 4943, 4944, 4945, 4946, 4947, 4948, 4949, 4950, 4951, 4952, 4953, 4954, 4955, 4956, 4957, 4958, 4959, 4960, 4961, 4962, 4963, 4964, 4965, 4966, 4967, 4968, 4969, 4970, 4971, 4972, 4973, 4974, 4975, 4976, 4977, 4978, 4979, 4980, 4981, 4982, 4983, 4984, 4985, 4986, 4987, 4988, 4989, 4990, 4991, 4992, 4993, 4994, 4995, 4996, 4997, 4998, 4999, 5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050, 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, 5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 5159, 5160, 5161, 5162, 5163, 5164, 5165, 5166, 5167, 5168, 5169, 5170, 5171, 5172, 5173, 5174, 5175, 5176, 5177, 5178, 5179, 5180, 5181, 5182, 5183, 5184, 5185, 5186, 5187, 5188, 5189, 5190, 5191, 5192, 5193, 5194, 5195, 5196, 5197, 5198, 5199, 5200, 5201, 5202, 5203, 5204, 5205, 5206, 5207, 5208, 5209, 5210, 5211, 5212, 5213, 5214, 5215, 5216, 5217, 5218, 5219, 5220);
DELETE FROM participations WHERE room_id IN (4921, 4922, 4923, 4924, 4925, 4926, 4927, 4928, 4929, 4930, 4931, 4932, 4933, 4934, 4935, 4936, 4937, 4938, 4939, 4940, 4941, 4942, 4943, 4944, 4945, 4946, 4947, 4948, 4949, 4950, 4951, 4952, 4953, 4954, 4955, 4956, 4957, 4958, 4959, 4960, 4961, 4962, 4963, 4964, 4965, 4966, 4967, 4968, 4969, 4970, 4971, 4972, 4973, 4974, 4975, 4976, 4977, 4978, 4979, 4980, 4981, 4982, 4983, 4984, 4985, 4986, 4987, 4988, 4989, 4990, 4991, 4992, 4993, 4994, 4995, 4996, 4997, 4998, 4999, 5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050, 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, 5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 5159, 5160, 5161, 5162, 5163, 5164, 5165, 5166, 5167, 5168, 5169, 5170, 5171, 5172, 5173, 5174, 5175, 5176, 5177, 5178, 5179, 5180, 5181, 5182, 5183, 5184, 5185, 5186, 5187, 5188, 5189, 5190, 5191, 5192, 5193, 5194, 5195, 5196, 5197, 5198, 5199, 5200, 5201, 5202, 5203, 5204, 5205, 5206, 5207, 5208, 5209, 5210, 5211, 5212, 5213, 5214, 5215, 5216, 5217, 5218, 5219, 5220);
DELETE FROM rooms WHERE id IN (4921, 4922, 4923, 4924, 4925, 4926, 4927, 4928, 4929, 4930, 4931, 4932, 4933, 4934, 4935, 4936, 4937, 4938, 4939, 4940, 4941, 4942, 4943, 4944, 4945, 4946, 4947, 4948, 4949, 4950, 4951, 4952, 4953, 4954, 4955, 4956, 4957, 4958, 4959, 4960, 4961, 4962, 4963, 4964, 4965, 4966, 4967, 4968, 4969, 4970, 4971, 4972, 4973, 4974, 4975, 4976, 4977, 4978, 4979, 4980, 4981, 4982, 4983, 4984, 4985, 4986, 4987, 4988, 4989, 4990, 4991, 4992, 4993, 4994, 4995, 4996, 4997, 4998, 4999, 5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050, 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, 5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 5159, 5160, 5161, 5162, 5163, 5164, 5165, 5166, 5167, 5168, 5169, 5170, 5171, 5172, 5173, 5174, 5175, 5176, 5177, 5178, 5179, 5180, 5181, 5182, 5183, 5184, 5185, 5186, 5187, 5188, 5189, 5190, 5191, 5192, 5193, 5194, 5195, 5196, 5197, 5198, 5199, 5200, 5201, 5202, 5203, 5204, 5205, 5206, 5207, 5208, 5209, 5210, 5211, 5212, 5213, 5214, 5215, 5216, 5217, 5218, 5219, 5220);
DELETE FROM users WHERE id IN (960, 961, 962, 963, 964, 965, 966, 967, 968, 969, 970, 971, 972, 973, 974, 975, 976, 977, 978, 979, 980, 981, 982, 983, 984, 985);

COMMIT;
