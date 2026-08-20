\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-21dccaa5a7cf'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (908, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-host@example.invalid'),
    (909, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-cancel-0@example.invalid'),
    (910, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-cancel-1@example.invalid'),
    (911, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-cancel-2@example.invalid'),
    (912, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-cancel-3@example.invalid'),
    (913, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-waiter-0@example.invalid'),
    (914, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-waiter-1@example.invalid'),
    (915, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-waiter-2@example.invalid'),
    (916, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-waiter-3@example.invalid'),
    (917, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-mixed-waiter-4@example.invalid'),
    (918, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s4-host@example.invalid'),
    (919, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s4-cancel@example.invalid'),
    (920, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s4-waiter-0@example.invalid'),
    (921, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s4-waiter-1@example.invalid'),
    (922, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s5-host@example.invalid'),
    (923, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s5-cancel@example.invalid'),
    (924, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s5-waiter-0@example.invalid'),
    (925, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s5-waiter-1@example.invalid'),
    (926, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s6-host@example.invalid'),
    (927, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s6-cancel@example.invalid'),
    (928, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s6-waiter-0@example.invalid'),
    (929, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s6-waiter-1@example.invalid'),
    (930, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s7-host@example.invalid'),
    (931, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s7-cancel@example.invalid'),
    (932, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s7-waiter-0@example.invalid'),
    (933, 'room-k6.room-k6-t1-21dccaa5a7cf.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (4321, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r0-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4322, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r0-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4323, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r0-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4324, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r0-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4325, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r0-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4326, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r1-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4327, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r1-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4328, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r1-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4329, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r1-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4330, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r1-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4331, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r2-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4332, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r2-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4333, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r2-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4334, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r2-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4335, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r2-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4336, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r3-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4337, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r3-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4338, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r3-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4339, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r3-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4340, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r3-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4341, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r4-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4342, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r4-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4343, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r4-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4344, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r4-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4345, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r4-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4346, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r5-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4347, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r5-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4348, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r5-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4349, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r5-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4350, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r5-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4351, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r6-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4352, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r6-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4353, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r6-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4354, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r6-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4355, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r6-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4356, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r7-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4357, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r7-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4358, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r7-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4359, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r7-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4360, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r7-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4361, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r8-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4362, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r8-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4363, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r8-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4364, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r8-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4365, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r8-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4366, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r9-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4367, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r9-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4368, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r9-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4369, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r9-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4370, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r9-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4371, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r10-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4372, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r10-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4373, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r10-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4374, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r10-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4375, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r10-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4376, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r11-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4377, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r11-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4378, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r11-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4379, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r11-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4380, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r11-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4381, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r12-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4382, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r12-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4383, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r12-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4384, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r12-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4385, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r12-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4386, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r13-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4387, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r13-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4388, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r13-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4389, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r13-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4390, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r13-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4391, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r14-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4392, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r14-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4393, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r14-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4394, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r14-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4395, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r14-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4396, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r15-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4397, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r15-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4398, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r15-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4399, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r15-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4400, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r15-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4401, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r16-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4402, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r16-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4403, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r16-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4404, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r16-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4405, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r16-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4406, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r17-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4407, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r17-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4408, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r17-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4409, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r17-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4410, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r17-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4411, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r18-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4412, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r18-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4413, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r18-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4414, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r18-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4415, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r18-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4416, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r19-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4417, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r19-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4418, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r19-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4419, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r19-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4420, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r19-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4421, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r20-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4422, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r20-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4423, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r20-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4424, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r20-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4425, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r20-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4426, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r21-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4427, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r21-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4428, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r21-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4429, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r21-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4430, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r21-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4431, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r22-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4432, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r22-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4433, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r22-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4434, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r22-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4435, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r22-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4436, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r23-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4437, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r23-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4438, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r23-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4439, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r23-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4440, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r23-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4441, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r24-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4442, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r24-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4443, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r24-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4444, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r24-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4445, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r24-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4446, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r25-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4447, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r25-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4448, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r25-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4449, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r25-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4450, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r25-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4451, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r26-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4452, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r26-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4453, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r26-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4454, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r26-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4455, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r26-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4456, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r27-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4457, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r27-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4458, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r27-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4459, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r27-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4460, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r27-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4461, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r28-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4462, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r28-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4463, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r28-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4464, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r28-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4465, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r28-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4466, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r29-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4467, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r29-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4468, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r29-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4469, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r29-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4470, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r29-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4471, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r30-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4472, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r30-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4473, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r30-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4474, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r30-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4475, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r30-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4476, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r31-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4477, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r31-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4478, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r31-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4479, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r31-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4480, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r31-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4481, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r32-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4482, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r32-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4483, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r32-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4484, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r32-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4485, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r32-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4486, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r33-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4487, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r33-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4488, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r33-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4489, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r33-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4490, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r33-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4491, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r34-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4492, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r34-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4493, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r34-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4494, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r34-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4495, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r34-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4496, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r35-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4497, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r35-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4498, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r35-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4499, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r35-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4500, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r35-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4501, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r36-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4502, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r36-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4503, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r36-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4504, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r36-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4505, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r36-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4506, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r37-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4507, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r37-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4508, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r37-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4509, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r37-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4510, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r37-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4511, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r38-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4512, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r38-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4513, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r38-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4514, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r38-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4515, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r38-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4516, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r39-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4517, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r39-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4518, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r39-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4519, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r39-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4520, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r39-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4521, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r40-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4522, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r40-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4523, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r40-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4524, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r40-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4525, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r40-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4526, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r41-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4527, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r41-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4528, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r41-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4529, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r41-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4530, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r41-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4531, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r42-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4532, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r42-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4533, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r42-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4534, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r42-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4535, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r42-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4536, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r43-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4537, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r43-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4538, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r43-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4539, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r43-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4540, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r43-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4541, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r44-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4542, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r44-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4543, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r44-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4544, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r44-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4545, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r44-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4546, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r45-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4547, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r45-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4548, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r45-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4549, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r45-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4550, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r45-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4551, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r46-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4552, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r46-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4553, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r46-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4554, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r46-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4555, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r46-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4556, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r47-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4557, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r47-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4558, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r47-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4559, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r47-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4560, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r47-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4561, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r48-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4562, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r48-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4563, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r48-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4564, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r48-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4565, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r48-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4566, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r49-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4567, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r49-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4568, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r49-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4569, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r49-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4570, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r49-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4571, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r50-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4572, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r50-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4573, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r50-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4574, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r50-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4575, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r50-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4576, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r51-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4577, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r51-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4578, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r51-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4579, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r51-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4580, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r51-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4581, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r52-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4582, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r52-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4583, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r52-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4584, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r52-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4585, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r52-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4586, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r53-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4587, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r53-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4588, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r53-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4589, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r53-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4590, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r53-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4591, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r54-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4592, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r54-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4593, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r54-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4594, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r54-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4595, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r54-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4596, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r55-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4597, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r55-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4598, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r55-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4599, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r55-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4600, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r55-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4601, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r56-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4602, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r56-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4603, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r56-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4604, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r56-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4605, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r56-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4606, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r57-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4607, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r57-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4608, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r57-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4609, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r57-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4610, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r57-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4611, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r58-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4612, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r58-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4613, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r58-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4614, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r58-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4615, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r58-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4616, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r59-mixed-hot', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4617, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r59-spread-s4', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4618, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r59-spread-s5', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4619, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r59-spread-s6', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b'),
    (4620, 'ROOM-K6 room-k6-t1-21dccaa5a7cf t1-r59-spread-s7', 'ROOM k6 fixture 2d7d0abe0a0b411aa75d2b485f3fe29b');

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

DELETE FROM notifications WHERE room_id IN (4321, 4322, 4323, 4324, 4325, 4326, 4327, 4328, 4329, 4330, 4331, 4332, 4333, 4334, 4335, 4336, 4337, 4338, 4339, 4340, 4341, 4342, 4343, 4344, 4345, 4346, 4347, 4348, 4349, 4350, 4351, 4352, 4353, 4354, 4355, 4356, 4357, 4358, 4359, 4360, 4361, 4362, 4363, 4364, 4365, 4366, 4367, 4368, 4369, 4370, 4371, 4372, 4373, 4374, 4375, 4376, 4377, 4378, 4379, 4380, 4381, 4382, 4383, 4384, 4385, 4386, 4387, 4388, 4389, 4390, 4391, 4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399, 4400, 4401, 4402, 4403, 4404, 4405, 4406, 4407, 4408, 4409, 4410, 4411, 4412, 4413, 4414, 4415, 4416, 4417, 4418, 4419, 4420, 4421, 4422, 4423, 4424, 4425, 4426, 4427, 4428, 4429, 4430, 4431, 4432, 4433, 4434, 4435, 4436, 4437, 4438, 4439, 4440, 4441, 4442, 4443, 4444, 4445, 4446, 4447, 4448, 4449, 4450, 4451, 4452, 4453, 4454, 4455, 4456, 4457, 4458, 4459, 4460, 4461, 4462, 4463, 4464, 4465, 4466, 4467, 4468, 4469, 4470, 4471, 4472, 4473, 4474, 4475, 4476, 4477, 4478, 4479, 4480, 4481, 4482, 4483, 4484, 4485, 4486, 4487, 4488, 4489, 4490, 4491, 4492, 4493, 4494, 4495, 4496, 4497, 4498, 4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510, 4511, 4512, 4513, 4514, 4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525, 4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536, 4537, 4538, 4539, 4540, 4541, 4542, 4543, 4544, 4545, 4546, 4547, 4548, 4549, 4550, 4551, 4552, 4553, 4554, 4555, 4556, 4557, 4558, 4559, 4560, 4561, 4562, 4563, 4564, 4565, 4566, 4567, 4568, 4569, 4570, 4571, 4572, 4573, 4574, 4575, 4576, 4577, 4578, 4579, 4580, 4581, 4582, 4583, 4584, 4585, 4586, 4587, 4588, 4589, 4590, 4591, 4592, 4593, 4594, 4595, 4596, 4597, 4598, 4599, 4600, 4601, 4602, 4603, 4604, 4605, 4606, 4607, 4608, 4609, 4610, 4611, 4612, 4613, 4614, 4615, 4616, 4617, 4618, 4619, 4620);
DELETE FROM notification_outbox_events WHERE room_id IN (4321, 4322, 4323, 4324, 4325, 4326, 4327, 4328, 4329, 4330, 4331, 4332, 4333, 4334, 4335, 4336, 4337, 4338, 4339, 4340, 4341, 4342, 4343, 4344, 4345, 4346, 4347, 4348, 4349, 4350, 4351, 4352, 4353, 4354, 4355, 4356, 4357, 4358, 4359, 4360, 4361, 4362, 4363, 4364, 4365, 4366, 4367, 4368, 4369, 4370, 4371, 4372, 4373, 4374, 4375, 4376, 4377, 4378, 4379, 4380, 4381, 4382, 4383, 4384, 4385, 4386, 4387, 4388, 4389, 4390, 4391, 4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399, 4400, 4401, 4402, 4403, 4404, 4405, 4406, 4407, 4408, 4409, 4410, 4411, 4412, 4413, 4414, 4415, 4416, 4417, 4418, 4419, 4420, 4421, 4422, 4423, 4424, 4425, 4426, 4427, 4428, 4429, 4430, 4431, 4432, 4433, 4434, 4435, 4436, 4437, 4438, 4439, 4440, 4441, 4442, 4443, 4444, 4445, 4446, 4447, 4448, 4449, 4450, 4451, 4452, 4453, 4454, 4455, 4456, 4457, 4458, 4459, 4460, 4461, 4462, 4463, 4464, 4465, 4466, 4467, 4468, 4469, 4470, 4471, 4472, 4473, 4474, 4475, 4476, 4477, 4478, 4479, 4480, 4481, 4482, 4483, 4484, 4485, 4486, 4487, 4488, 4489, 4490, 4491, 4492, 4493, 4494, 4495, 4496, 4497, 4498, 4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510, 4511, 4512, 4513, 4514, 4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525, 4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536, 4537, 4538, 4539, 4540, 4541, 4542, 4543, 4544, 4545, 4546, 4547, 4548, 4549, 4550, 4551, 4552, 4553, 4554, 4555, 4556, 4557, 4558, 4559, 4560, 4561, 4562, 4563, 4564, 4565, 4566, 4567, 4568, 4569, 4570, 4571, 4572, 4573, 4574, 4575, 4576, 4577, 4578, 4579, 4580, 4581, 4582, 4583, 4584, 4585, 4586, 4587, 4588, 4589, 4590, 4591, 4592, 4593, 4594, 4595, 4596, 4597, 4598, 4599, 4600, 4601, 4602, 4603, 4604, 4605, 4606, 4607, 4608, 4609, 4610, 4611, 4612, 4613, 4614, 4615, 4616, 4617, 4618, 4619, 4620);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (4321, 4322, 4323, 4324, 4325, 4326, 4327, 4328, 4329, 4330, 4331, 4332, 4333, 4334, 4335, 4336, 4337, 4338, 4339, 4340, 4341, 4342, 4343, 4344, 4345, 4346, 4347, 4348, 4349, 4350, 4351, 4352, 4353, 4354, 4355, 4356, 4357, 4358, 4359, 4360, 4361, 4362, 4363, 4364, 4365, 4366, 4367, 4368, 4369, 4370, 4371, 4372, 4373, 4374, 4375, 4376, 4377, 4378, 4379, 4380, 4381, 4382, 4383, 4384, 4385, 4386, 4387, 4388, 4389, 4390, 4391, 4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399, 4400, 4401, 4402, 4403, 4404, 4405, 4406, 4407, 4408, 4409, 4410, 4411, 4412, 4413, 4414, 4415, 4416, 4417, 4418, 4419, 4420, 4421, 4422, 4423, 4424, 4425, 4426, 4427, 4428, 4429, 4430, 4431, 4432, 4433, 4434, 4435, 4436, 4437, 4438, 4439, 4440, 4441, 4442, 4443, 4444, 4445, 4446, 4447, 4448, 4449, 4450, 4451, 4452, 4453, 4454, 4455, 4456, 4457, 4458, 4459, 4460, 4461, 4462, 4463, 4464, 4465, 4466, 4467, 4468, 4469, 4470, 4471, 4472, 4473, 4474, 4475, 4476, 4477, 4478, 4479, 4480, 4481, 4482, 4483, 4484, 4485, 4486, 4487, 4488, 4489, 4490, 4491, 4492, 4493, 4494, 4495, 4496, 4497, 4498, 4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510, 4511, 4512, 4513, 4514, 4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525, 4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536, 4537, 4538, 4539, 4540, 4541, 4542, 4543, 4544, 4545, 4546, 4547, 4548, 4549, 4550, 4551, 4552, 4553, 4554, 4555, 4556, 4557, 4558, 4559, 4560, 4561, 4562, 4563, 4564, 4565, 4566, 4567, 4568, 4569, 4570, 4571, 4572, 4573, 4574, 4575, 4576, 4577, 4578, 4579, 4580, 4581, 4582, 4583, 4584, 4585, 4586, 4587, 4588, 4589, 4590, 4591, 4592, 4593, 4594, 4595, 4596, 4597, 4598, 4599, 4600, 4601, 4602, 4603, 4604, 4605, 4606, 4607, 4608, 4609, 4610, 4611, 4612, 4613, 4614, 4615, 4616, 4617, 4618, 4619, 4620)
);
DELETE FROM chat_rooms WHERE room_id IN (4321, 4322, 4323, 4324, 4325, 4326, 4327, 4328, 4329, 4330, 4331, 4332, 4333, 4334, 4335, 4336, 4337, 4338, 4339, 4340, 4341, 4342, 4343, 4344, 4345, 4346, 4347, 4348, 4349, 4350, 4351, 4352, 4353, 4354, 4355, 4356, 4357, 4358, 4359, 4360, 4361, 4362, 4363, 4364, 4365, 4366, 4367, 4368, 4369, 4370, 4371, 4372, 4373, 4374, 4375, 4376, 4377, 4378, 4379, 4380, 4381, 4382, 4383, 4384, 4385, 4386, 4387, 4388, 4389, 4390, 4391, 4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399, 4400, 4401, 4402, 4403, 4404, 4405, 4406, 4407, 4408, 4409, 4410, 4411, 4412, 4413, 4414, 4415, 4416, 4417, 4418, 4419, 4420, 4421, 4422, 4423, 4424, 4425, 4426, 4427, 4428, 4429, 4430, 4431, 4432, 4433, 4434, 4435, 4436, 4437, 4438, 4439, 4440, 4441, 4442, 4443, 4444, 4445, 4446, 4447, 4448, 4449, 4450, 4451, 4452, 4453, 4454, 4455, 4456, 4457, 4458, 4459, 4460, 4461, 4462, 4463, 4464, 4465, 4466, 4467, 4468, 4469, 4470, 4471, 4472, 4473, 4474, 4475, 4476, 4477, 4478, 4479, 4480, 4481, 4482, 4483, 4484, 4485, 4486, 4487, 4488, 4489, 4490, 4491, 4492, 4493, 4494, 4495, 4496, 4497, 4498, 4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510, 4511, 4512, 4513, 4514, 4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525, 4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536, 4537, 4538, 4539, 4540, 4541, 4542, 4543, 4544, 4545, 4546, 4547, 4548, 4549, 4550, 4551, 4552, 4553, 4554, 4555, 4556, 4557, 4558, 4559, 4560, 4561, 4562, 4563, 4564, 4565, 4566, 4567, 4568, 4569, 4570, 4571, 4572, 4573, 4574, 4575, 4576, 4577, 4578, 4579, 4580, 4581, 4582, 4583, 4584, 4585, 4586, 4587, 4588, 4589, 4590, 4591, 4592, 4593, 4594, 4595, 4596, 4597, 4598, 4599, 4600, 4601, 4602, 4603, 4604, 4605, 4606, 4607, 4608, 4609, 4610, 4611, 4612, 4613, 4614, 4615, 4616, 4617, 4618, 4619, 4620);
DELETE FROM room_waitlists WHERE room_id IN (4321, 4322, 4323, 4324, 4325, 4326, 4327, 4328, 4329, 4330, 4331, 4332, 4333, 4334, 4335, 4336, 4337, 4338, 4339, 4340, 4341, 4342, 4343, 4344, 4345, 4346, 4347, 4348, 4349, 4350, 4351, 4352, 4353, 4354, 4355, 4356, 4357, 4358, 4359, 4360, 4361, 4362, 4363, 4364, 4365, 4366, 4367, 4368, 4369, 4370, 4371, 4372, 4373, 4374, 4375, 4376, 4377, 4378, 4379, 4380, 4381, 4382, 4383, 4384, 4385, 4386, 4387, 4388, 4389, 4390, 4391, 4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399, 4400, 4401, 4402, 4403, 4404, 4405, 4406, 4407, 4408, 4409, 4410, 4411, 4412, 4413, 4414, 4415, 4416, 4417, 4418, 4419, 4420, 4421, 4422, 4423, 4424, 4425, 4426, 4427, 4428, 4429, 4430, 4431, 4432, 4433, 4434, 4435, 4436, 4437, 4438, 4439, 4440, 4441, 4442, 4443, 4444, 4445, 4446, 4447, 4448, 4449, 4450, 4451, 4452, 4453, 4454, 4455, 4456, 4457, 4458, 4459, 4460, 4461, 4462, 4463, 4464, 4465, 4466, 4467, 4468, 4469, 4470, 4471, 4472, 4473, 4474, 4475, 4476, 4477, 4478, 4479, 4480, 4481, 4482, 4483, 4484, 4485, 4486, 4487, 4488, 4489, 4490, 4491, 4492, 4493, 4494, 4495, 4496, 4497, 4498, 4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510, 4511, 4512, 4513, 4514, 4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525, 4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536, 4537, 4538, 4539, 4540, 4541, 4542, 4543, 4544, 4545, 4546, 4547, 4548, 4549, 4550, 4551, 4552, 4553, 4554, 4555, 4556, 4557, 4558, 4559, 4560, 4561, 4562, 4563, 4564, 4565, 4566, 4567, 4568, 4569, 4570, 4571, 4572, 4573, 4574, 4575, 4576, 4577, 4578, 4579, 4580, 4581, 4582, 4583, 4584, 4585, 4586, 4587, 4588, 4589, 4590, 4591, 4592, 4593, 4594, 4595, 4596, 4597, 4598, 4599, 4600, 4601, 4602, 4603, 4604, 4605, 4606, 4607, 4608, 4609, 4610, 4611, 4612, 4613, 4614, 4615, 4616, 4617, 4618, 4619, 4620);
DELETE FROM participations WHERE room_id IN (4321, 4322, 4323, 4324, 4325, 4326, 4327, 4328, 4329, 4330, 4331, 4332, 4333, 4334, 4335, 4336, 4337, 4338, 4339, 4340, 4341, 4342, 4343, 4344, 4345, 4346, 4347, 4348, 4349, 4350, 4351, 4352, 4353, 4354, 4355, 4356, 4357, 4358, 4359, 4360, 4361, 4362, 4363, 4364, 4365, 4366, 4367, 4368, 4369, 4370, 4371, 4372, 4373, 4374, 4375, 4376, 4377, 4378, 4379, 4380, 4381, 4382, 4383, 4384, 4385, 4386, 4387, 4388, 4389, 4390, 4391, 4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399, 4400, 4401, 4402, 4403, 4404, 4405, 4406, 4407, 4408, 4409, 4410, 4411, 4412, 4413, 4414, 4415, 4416, 4417, 4418, 4419, 4420, 4421, 4422, 4423, 4424, 4425, 4426, 4427, 4428, 4429, 4430, 4431, 4432, 4433, 4434, 4435, 4436, 4437, 4438, 4439, 4440, 4441, 4442, 4443, 4444, 4445, 4446, 4447, 4448, 4449, 4450, 4451, 4452, 4453, 4454, 4455, 4456, 4457, 4458, 4459, 4460, 4461, 4462, 4463, 4464, 4465, 4466, 4467, 4468, 4469, 4470, 4471, 4472, 4473, 4474, 4475, 4476, 4477, 4478, 4479, 4480, 4481, 4482, 4483, 4484, 4485, 4486, 4487, 4488, 4489, 4490, 4491, 4492, 4493, 4494, 4495, 4496, 4497, 4498, 4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510, 4511, 4512, 4513, 4514, 4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525, 4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536, 4537, 4538, 4539, 4540, 4541, 4542, 4543, 4544, 4545, 4546, 4547, 4548, 4549, 4550, 4551, 4552, 4553, 4554, 4555, 4556, 4557, 4558, 4559, 4560, 4561, 4562, 4563, 4564, 4565, 4566, 4567, 4568, 4569, 4570, 4571, 4572, 4573, 4574, 4575, 4576, 4577, 4578, 4579, 4580, 4581, 4582, 4583, 4584, 4585, 4586, 4587, 4588, 4589, 4590, 4591, 4592, 4593, 4594, 4595, 4596, 4597, 4598, 4599, 4600, 4601, 4602, 4603, 4604, 4605, 4606, 4607, 4608, 4609, 4610, 4611, 4612, 4613, 4614, 4615, 4616, 4617, 4618, 4619, 4620);
DELETE FROM rooms WHERE id IN (4321, 4322, 4323, 4324, 4325, 4326, 4327, 4328, 4329, 4330, 4331, 4332, 4333, 4334, 4335, 4336, 4337, 4338, 4339, 4340, 4341, 4342, 4343, 4344, 4345, 4346, 4347, 4348, 4349, 4350, 4351, 4352, 4353, 4354, 4355, 4356, 4357, 4358, 4359, 4360, 4361, 4362, 4363, 4364, 4365, 4366, 4367, 4368, 4369, 4370, 4371, 4372, 4373, 4374, 4375, 4376, 4377, 4378, 4379, 4380, 4381, 4382, 4383, 4384, 4385, 4386, 4387, 4388, 4389, 4390, 4391, 4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399, 4400, 4401, 4402, 4403, 4404, 4405, 4406, 4407, 4408, 4409, 4410, 4411, 4412, 4413, 4414, 4415, 4416, 4417, 4418, 4419, 4420, 4421, 4422, 4423, 4424, 4425, 4426, 4427, 4428, 4429, 4430, 4431, 4432, 4433, 4434, 4435, 4436, 4437, 4438, 4439, 4440, 4441, 4442, 4443, 4444, 4445, 4446, 4447, 4448, 4449, 4450, 4451, 4452, 4453, 4454, 4455, 4456, 4457, 4458, 4459, 4460, 4461, 4462, 4463, 4464, 4465, 4466, 4467, 4468, 4469, 4470, 4471, 4472, 4473, 4474, 4475, 4476, 4477, 4478, 4479, 4480, 4481, 4482, 4483, 4484, 4485, 4486, 4487, 4488, 4489, 4490, 4491, 4492, 4493, 4494, 4495, 4496, 4497, 4498, 4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510, 4511, 4512, 4513, 4514, 4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525, 4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536, 4537, 4538, 4539, 4540, 4541, 4542, 4543, 4544, 4545, 4546, 4547, 4548, 4549, 4550, 4551, 4552, 4553, 4554, 4555, 4556, 4557, 4558, 4559, 4560, 4561, 4562, 4563, 4564, 4565, 4566, 4567, 4568, 4569, 4570, 4571, 4572, 4573, 4574, 4575, 4576, 4577, 4578, 4579, 4580, 4581, 4582, 4583, 4584, 4585, 4586, 4587, 4588, 4589, 4590, 4591, 4592, 4593, 4594, 4595, 4596, 4597, 4598, 4599, 4600, 4601, 4602, 4603, 4604, 4605, 4606, 4607, 4608, 4609, 4610, 4611, 4612, 4613, 4614, 4615, 4616, 4617, 4618, 4619, 4620);
DELETE FROM users WHERE id IN (908, 909, 910, 911, 912, 913, 914, 915, 916, 917, 918, 919, 920, 921, 922, 923, 924, 925, 926, 927, 928, 929, 930, 931, 932, 933);

COMMIT;
