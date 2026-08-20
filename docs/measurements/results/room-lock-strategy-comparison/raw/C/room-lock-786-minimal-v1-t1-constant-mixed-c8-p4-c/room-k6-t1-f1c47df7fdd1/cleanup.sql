\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-f1c47df7fdd1'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (882, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-host@example.invalid'),
    (883, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-cancel-0@example.invalid'),
    (884, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-cancel-1@example.invalid'),
    (885, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-cancel-2@example.invalid'),
    (886, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-cancel-3@example.invalid'),
    (887, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-waiter-0@example.invalid'),
    (888, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-waiter-1@example.invalid'),
    (889, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-waiter-2@example.invalid'),
    (890, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-waiter-3@example.invalid'),
    (891, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-mixed-waiter-4@example.invalid'),
    (892, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s4-host@example.invalid'),
    (893, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s4-cancel@example.invalid'),
    (894, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s4-waiter-0@example.invalid'),
    (895, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s4-waiter-1@example.invalid'),
    (896, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s5-host@example.invalid'),
    (897, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s5-cancel@example.invalid'),
    (898, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s5-waiter-0@example.invalid'),
    (899, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s5-waiter-1@example.invalid'),
    (900, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s6-host@example.invalid'),
    (901, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s6-cancel@example.invalid'),
    (902, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s6-waiter-0@example.invalid'),
    (903, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s6-waiter-1@example.invalid'),
    (904, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s7-host@example.invalid'),
    (905, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s7-cancel@example.invalid'),
    (906, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s7-waiter-0@example.invalid'),
    (907, 'room-k6.room-k6-t1-f1c47df7fdd1.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (4021, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r0-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4022, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r0-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4023, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r0-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4024, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r0-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4025, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r0-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4026, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r1-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4027, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r1-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4028, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r1-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4029, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r1-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4030, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r1-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4031, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r2-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4032, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r2-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4033, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r2-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4034, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r2-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4035, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r2-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4036, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r3-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4037, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r3-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4038, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r3-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4039, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r3-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4040, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r3-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4041, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r4-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4042, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r4-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4043, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r4-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4044, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r4-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4045, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r4-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4046, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r5-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4047, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r5-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4048, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r5-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4049, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r5-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4050, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r5-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4051, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r6-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4052, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r6-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4053, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r6-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4054, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r6-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4055, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r6-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4056, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r7-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4057, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r7-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4058, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r7-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4059, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r7-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4060, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r7-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4061, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r8-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4062, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r8-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4063, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r8-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4064, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r8-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4065, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r8-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4066, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r9-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4067, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r9-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4068, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r9-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4069, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r9-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4070, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r9-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4071, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r10-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4072, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r10-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4073, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r10-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4074, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r10-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4075, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r10-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4076, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r11-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4077, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r11-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4078, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r11-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4079, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r11-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4080, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r11-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4081, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r12-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4082, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r12-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4083, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r12-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4084, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r12-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4085, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r12-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4086, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r13-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4087, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r13-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4088, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r13-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4089, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r13-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4090, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r13-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4091, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r14-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4092, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r14-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4093, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r14-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4094, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r14-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4095, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r14-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4096, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r15-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4097, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r15-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4098, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r15-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4099, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r15-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4100, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r15-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4101, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r16-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4102, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r16-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4103, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r16-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4104, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r16-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4105, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r16-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4106, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r17-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4107, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r17-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4108, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r17-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4109, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r17-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4110, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r17-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4111, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r18-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4112, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r18-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4113, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r18-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4114, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r18-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4115, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r18-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4116, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r19-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4117, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r19-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4118, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r19-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4119, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r19-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4120, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r19-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4121, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r20-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4122, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r20-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4123, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r20-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4124, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r20-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4125, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r20-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4126, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r21-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4127, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r21-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4128, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r21-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4129, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r21-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4130, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r21-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4131, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r22-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4132, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r22-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4133, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r22-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4134, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r22-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4135, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r22-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4136, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r23-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4137, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r23-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4138, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r23-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4139, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r23-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4140, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r23-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4141, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r24-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4142, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r24-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4143, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r24-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4144, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r24-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4145, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r24-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4146, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r25-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4147, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r25-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4148, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r25-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4149, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r25-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4150, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r25-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4151, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r26-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4152, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r26-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4153, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r26-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4154, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r26-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4155, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r26-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4156, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r27-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4157, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r27-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4158, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r27-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4159, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r27-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4160, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r27-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4161, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r28-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4162, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r28-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4163, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r28-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4164, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r28-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4165, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r28-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4166, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r29-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4167, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r29-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4168, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r29-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4169, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r29-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4170, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r29-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4171, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r30-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4172, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r30-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4173, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r30-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4174, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r30-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4175, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r30-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4176, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r31-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4177, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r31-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4178, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r31-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4179, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r31-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4180, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r31-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4181, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r32-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4182, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r32-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4183, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r32-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4184, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r32-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4185, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r32-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4186, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r33-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4187, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r33-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4188, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r33-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4189, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r33-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4190, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r33-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4191, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r34-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4192, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r34-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4193, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r34-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4194, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r34-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4195, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r34-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4196, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r35-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4197, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r35-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4198, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r35-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4199, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r35-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4200, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r35-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4201, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r36-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4202, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r36-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4203, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r36-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4204, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r36-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4205, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r36-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4206, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r37-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4207, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r37-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4208, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r37-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4209, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r37-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4210, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r37-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4211, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r38-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4212, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r38-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4213, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r38-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4214, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r38-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4215, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r38-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4216, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r39-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4217, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r39-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4218, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r39-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4219, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r39-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4220, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r39-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4221, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r40-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4222, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r40-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4223, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r40-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4224, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r40-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4225, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r40-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4226, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r41-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4227, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r41-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4228, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r41-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4229, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r41-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4230, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r41-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4231, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r42-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4232, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r42-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4233, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r42-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4234, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r42-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4235, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r42-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4236, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r43-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4237, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r43-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4238, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r43-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4239, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r43-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4240, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r43-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4241, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r44-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4242, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r44-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4243, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r44-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4244, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r44-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4245, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r44-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4246, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r45-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4247, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r45-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4248, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r45-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4249, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r45-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4250, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r45-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4251, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r46-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4252, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r46-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4253, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r46-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4254, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r46-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4255, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r46-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4256, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r47-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4257, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r47-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4258, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r47-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4259, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r47-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4260, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r47-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4261, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r48-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4262, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r48-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4263, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r48-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4264, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r48-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4265, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r48-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4266, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r49-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4267, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r49-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4268, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r49-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4269, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r49-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4270, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r49-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4271, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r50-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4272, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r50-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4273, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r50-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4274, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r50-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4275, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r50-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4276, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r51-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4277, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r51-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4278, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r51-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4279, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r51-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4280, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r51-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4281, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r52-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4282, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r52-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4283, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r52-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4284, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r52-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4285, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r52-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4286, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r53-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4287, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r53-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4288, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r53-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4289, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r53-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4290, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r53-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4291, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r54-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4292, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r54-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4293, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r54-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4294, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r54-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4295, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r54-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4296, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r55-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4297, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r55-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4298, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r55-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4299, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r55-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4300, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r55-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4301, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r56-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4302, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r56-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4303, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r56-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4304, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r56-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4305, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r56-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4306, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r57-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4307, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r57-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4308, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r57-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4309, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r57-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4310, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r57-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4311, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r58-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4312, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r58-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4313, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r58-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4314, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r58-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4315, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r58-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4316, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r59-mixed-hot', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4317, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r59-spread-s4', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4318, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r59-spread-s5', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4319, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r59-spread-s6', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369'),
    (4320, 'ROOM-K6 room-k6-t1-f1c47df7fdd1 t1-r59-spread-s7', 'ROOM k6 fixture a436a8cb99144053841623c5a3110369');

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

DELETE FROM notifications WHERE room_id IN (4021, 4022, 4023, 4024, 4025, 4026, 4027, 4028, 4029, 4030, 4031, 4032, 4033, 4034, 4035, 4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047, 4048, 4049, 4050, 4051, 4052, 4053, 4054, 4055, 4056, 4057, 4058, 4059, 4060, 4061, 4062, 4063, 4064, 4065, 4066, 4067, 4068, 4069, 4070, 4071, 4072, 4073, 4074, 4075, 4076, 4077, 4078, 4079, 4080, 4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089, 4090, 4091, 4092, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110, 4111, 4112, 4113, 4114, 4115, 4116, 4117, 4118, 4119, 4120, 4121, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 4130, 4131, 4132, 4133, 4134, 4135, 4136, 4137, 4138, 4139, 4140, 4141, 4142, 4143, 4144, 4145, 4146, 4147, 4148, 4149, 4150, 4151, 4152, 4153, 4154, 4155, 4156, 4157, 4158, 4159, 4160, 4161, 4162, 4163, 4164, 4165, 4166, 4167, 4168, 4169, 4170, 4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179, 4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191, 4192, 4193, 4194, 4195, 4196, 4197, 4198, 4199, 4200, 4201, 4202, 4203, 4204, 4205, 4206, 4207, 4208, 4209, 4210, 4211, 4212, 4213, 4214, 4215, 4216, 4217, 4218, 4219, 4220, 4221, 4222, 4223, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4232, 4233, 4234, 4235, 4236, 4237, 4238, 4239, 4240, 4241, 4242, 4243, 4244, 4245, 4246, 4247, 4248, 4249, 4250, 4251, 4252, 4253, 4254, 4255, 4256, 4257, 4258, 4259, 4260, 4261, 4262, 4263, 4264, 4265, 4266, 4267, 4268, 4269, 4270, 4271, 4272, 4273, 4274, 4275, 4276, 4277, 4278, 4279, 4280, 4281, 4282, 4283, 4284, 4285, 4286, 4287, 4288, 4289, 4290, 4291, 4292, 4293, 4294, 4295, 4296, 4297, 4298, 4299, 4300, 4301, 4302, 4303, 4304, 4305, 4306, 4307, 4308, 4309, 4310, 4311, 4312, 4313, 4314, 4315, 4316, 4317, 4318, 4319, 4320);
DELETE FROM notification_outbox_events WHERE room_id IN (4021, 4022, 4023, 4024, 4025, 4026, 4027, 4028, 4029, 4030, 4031, 4032, 4033, 4034, 4035, 4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047, 4048, 4049, 4050, 4051, 4052, 4053, 4054, 4055, 4056, 4057, 4058, 4059, 4060, 4061, 4062, 4063, 4064, 4065, 4066, 4067, 4068, 4069, 4070, 4071, 4072, 4073, 4074, 4075, 4076, 4077, 4078, 4079, 4080, 4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089, 4090, 4091, 4092, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110, 4111, 4112, 4113, 4114, 4115, 4116, 4117, 4118, 4119, 4120, 4121, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 4130, 4131, 4132, 4133, 4134, 4135, 4136, 4137, 4138, 4139, 4140, 4141, 4142, 4143, 4144, 4145, 4146, 4147, 4148, 4149, 4150, 4151, 4152, 4153, 4154, 4155, 4156, 4157, 4158, 4159, 4160, 4161, 4162, 4163, 4164, 4165, 4166, 4167, 4168, 4169, 4170, 4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179, 4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191, 4192, 4193, 4194, 4195, 4196, 4197, 4198, 4199, 4200, 4201, 4202, 4203, 4204, 4205, 4206, 4207, 4208, 4209, 4210, 4211, 4212, 4213, 4214, 4215, 4216, 4217, 4218, 4219, 4220, 4221, 4222, 4223, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4232, 4233, 4234, 4235, 4236, 4237, 4238, 4239, 4240, 4241, 4242, 4243, 4244, 4245, 4246, 4247, 4248, 4249, 4250, 4251, 4252, 4253, 4254, 4255, 4256, 4257, 4258, 4259, 4260, 4261, 4262, 4263, 4264, 4265, 4266, 4267, 4268, 4269, 4270, 4271, 4272, 4273, 4274, 4275, 4276, 4277, 4278, 4279, 4280, 4281, 4282, 4283, 4284, 4285, 4286, 4287, 4288, 4289, 4290, 4291, 4292, 4293, 4294, 4295, 4296, 4297, 4298, 4299, 4300, 4301, 4302, 4303, 4304, 4305, 4306, 4307, 4308, 4309, 4310, 4311, 4312, 4313, 4314, 4315, 4316, 4317, 4318, 4319, 4320);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (4021, 4022, 4023, 4024, 4025, 4026, 4027, 4028, 4029, 4030, 4031, 4032, 4033, 4034, 4035, 4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047, 4048, 4049, 4050, 4051, 4052, 4053, 4054, 4055, 4056, 4057, 4058, 4059, 4060, 4061, 4062, 4063, 4064, 4065, 4066, 4067, 4068, 4069, 4070, 4071, 4072, 4073, 4074, 4075, 4076, 4077, 4078, 4079, 4080, 4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089, 4090, 4091, 4092, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110, 4111, 4112, 4113, 4114, 4115, 4116, 4117, 4118, 4119, 4120, 4121, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 4130, 4131, 4132, 4133, 4134, 4135, 4136, 4137, 4138, 4139, 4140, 4141, 4142, 4143, 4144, 4145, 4146, 4147, 4148, 4149, 4150, 4151, 4152, 4153, 4154, 4155, 4156, 4157, 4158, 4159, 4160, 4161, 4162, 4163, 4164, 4165, 4166, 4167, 4168, 4169, 4170, 4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179, 4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191, 4192, 4193, 4194, 4195, 4196, 4197, 4198, 4199, 4200, 4201, 4202, 4203, 4204, 4205, 4206, 4207, 4208, 4209, 4210, 4211, 4212, 4213, 4214, 4215, 4216, 4217, 4218, 4219, 4220, 4221, 4222, 4223, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4232, 4233, 4234, 4235, 4236, 4237, 4238, 4239, 4240, 4241, 4242, 4243, 4244, 4245, 4246, 4247, 4248, 4249, 4250, 4251, 4252, 4253, 4254, 4255, 4256, 4257, 4258, 4259, 4260, 4261, 4262, 4263, 4264, 4265, 4266, 4267, 4268, 4269, 4270, 4271, 4272, 4273, 4274, 4275, 4276, 4277, 4278, 4279, 4280, 4281, 4282, 4283, 4284, 4285, 4286, 4287, 4288, 4289, 4290, 4291, 4292, 4293, 4294, 4295, 4296, 4297, 4298, 4299, 4300, 4301, 4302, 4303, 4304, 4305, 4306, 4307, 4308, 4309, 4310, 4311, 4312, 4313, 4314, 4315, 4316, 4317, 4318, 4319, 4320)
);
DELETE FROM chat_rooms WHERE room_id IN (4021, 4022, 4023, 4024, 4025, 4026, 4027, 4028, 4029, 4030, 4031, 4032, 4033, 4034, 4035, 4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047, 4048, 4049, 4050, 4051, 4052, 4053, 4054, 4055, 4056, 4057, 4058, 4059, 4060, 4061, 4062, 4063, 4064, 4065, 4066, 4067, 4068, 4069, 4070, 4071, 4072, 4073, 4074, 4075, 4076, 4077, 4078, 4079, 4080, 4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089, 4090, 4091, 4092, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110, 4111, 4112, 4113, 4114, 4115, 4116, 4117, 4118, 4119, 4120, 4121, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 4130, 4131, 4132, 4133, 4134, 4135, 4136, 4137, 4138, 4139, 4140, 4141, 4142, 4143, 4144, 4145, 4146, 4147, 4148, 4149, 4150, 4151, 4152, 4153, 4154, 4155, 4156, 4157, 4158, 4159, 4160, 4161, 4162, 4163, 4164, 4165, 4166, 4167, 4168, 4169, 4170, 4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179, 4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191, 4192, 4193, 4194, 4195, 4196, 4197, 4198, 4199, 4200, 4201, 4202, 4203, 4204, 4205, 4206, 4207, 4208, 4209, 4210, 4211, 4212, 4213, 4214, 4215, 4216, 4217, 4218, 4219, 4220, 4221, 4222, 4223, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4232, 4233, 4234, 4235, 4236, 4237, 4238, 4239, 4240, 4241, 4242, 4243, 4244, 4245, 4246, 4247, 4248, 4249, 4250, 4251, 4252, 4253, 4254, 4255, 4256, 4257, 4258, 4259, 4260, 4261, 4262, 4263, 4264, 4265, 4266, 4267, 4268, 4269, 4270, 4271, 4272, 4273, 4274, 4275, 4276, 4277, 4278, 4279, 4280, 4281, 4282, 4283, 4284, 4285, 4286, 4287, 4288, 4289, 4290, 4291, 4292, 4293, 4294, 4295, 4296, 4297, 4298, 4299, 4300, 4301, 4302, 4303, 4304, 4305, 4306, 4307, 4308, 4309, 4310, 4311, 4312, 4313, 4314, 4315, 4316, 4317, 4318, 4319, 4320);
DELETE FROM room_waitlists WHERE room_id IN (4021, 4022, 4023, 4024, 4025, 4026, 4027, 4028, 4029, 4030, 4031, 4032, 4033, 4034, 4035, 4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047, 4048, 4049, 4050, 4051, 4052, 4053, 4054, 4055, 4056, 4057, 4058, 4059, 4060, 4061, 4062, 4063, 4064, 4065, 4066, 4067, 4068, 4069, 4070, 4071, 4072, 4073, 4074, 4075, 4076, 4077, 4078, 4079, 4080, 4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089, 4090, 4091, 4092, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110, 4111, 4112, 4113, 4114, 4115, 4116, 4117, 4118, 4119, 4120, 4121, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 4130, 4131, 4132, 4133, 4134, 4135, 4136, 4137, 4138, 4139, 4140, 4141, 4142, 4143, 4144, 4145, 4146, 4147, 4148, 4149, 4150, 4151, 4152, 4153, 4154, 4155, 4156, 4157, 4158, 4159, 4160, 4161, 4162, 4163, 4164, 4165, 4166, 4167, 4168, 4169, 4170, 4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179, 4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191, 4192, 4193, 4194, 4195, 4196, 4197, 4198, 4199, 4200, 4201, 4202, 4203, 4204, 4205, 4206, 4207, 4208, 4209, 4210, 4211, 4212, 4213, 4214, 4215, 4216, 4217, 4218, 4219, 4220, 4221, 4222, 4223, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4232, 4233, 4234, 4235, 4236, 4237, 4238, 4239, 4240, 4241, 4242, 4243, 4244, 4245, 4246, 4247, 4248, 4249, 4250, 4251, 4252, 4253, 4254, 4255, 4256, 4257, 4258, 4259, 4260, 4261, 4262, 4263, 4264, 4265, 4266, 4267, 4268, 4269, 4270, 4271, 4272, 4273, 4274, 4275, 4276, 4277, 4278, 4279, 4280, 4281, 4282, 4283, 4284, 4285, 4286, 4287, 4288, 4289, 4290, 4291, 4292, 4293, 4294, 4295, 4296, 4297, 4298, 4299, 4300, 4301, 4302, 4303, 4304, 4305, 4306, 4307, 4308, 4309, 4310, 4311, 4312, 4313, 4314, 4315, 4316, 4317, 4318, 4319, 4320);
DELETE FROM participations WHERE room_id IN (4021, 4022, 4023, 4024, 4025, 4026, 4027, 4028, 4029, 4030, 4031, 4032, 4033, 4034, 4035, 4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047, 4048, 4049, 4050, 4051, 4052, 4053, 4054, 4055, 4056, 4057, 4058, 4059, 4060, 4061, 4062, 4063, 4064, 4065, 4066, 4067, 4068, 4069, 4070, 4071, 4072, 4073, 4074, 4075, 4076, 4077, 4078, 4079, 4080, 4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089, 4090, 4091, 4092, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110, 4111, 4112, 4113, 4114, 4115, 4116, 4117, 4118, 4119, 4120, 4121, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 4130, 4131, 4132, 4133, 4134, 4135, 4136, 4137, 4138, 4139, 4140, 4141, 4142, 4143, 4144, 4145, 4146, 4147, 4148, 4149, 4150, 4151, 4152, 4153, 4154, 4155, 4156, 4157, 4158, 4159, 4160, 4161, 4162, 4163, 4164, 4165, 4166, 4167, 4168, 4169, 4170, 4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179, 4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191, 4192, 4193, 4194, 4195, 4196, 4197, 4198, 4199, 4200, 4201, 4202, 4203, 4204, 4205, 4206, 4207, 4208, 4209, 4210, 4211, 4212, 4213, 4214, 4215, 4216, 4217, 4218, 4219, 4220, 4221, 4222, 4223, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4232, 4233, 4234, 4235, 4236, 4237, 4238, 4239, 4240, 4241, 4242, 4243, 4244, 4245, 4246, 4247, 4248, 4249, 4250, 4251, 4252, 4253, 4254, 4255, 4256, 4257, 4258, 4259, 4260, 4261, 4262, 4263, 4264, 4265, 4266, 4267, 4268, 4269, 4270, 4271, 4272, 4273, 4274, 4275, 4276, 4277, 4278, 4279, 4280, 4281, 4282, 4283, 4284, 4285, 4286, 4287, 4288, 4289, 4290, 4291, 4292, 4293, 4294, 4295, 4296, 4297, 4298, 4299, 4300, 4301, 4302, 4303, 4304, 4305, 4306, 4307, 4308, 4309, 4310, 4311, 4312, 4313, 4314, 4315, 4316, 4317, 4318, 4319, 4320);
DELETE FROM rooms WHERE id IN (4021, 4022, 4023, 4024, 4025, 4026, 4027, 4028, 4029, 4030, 4031, 4032, 4033, 4034, 4035, 4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047, 4048, 4049, 4050, 4051, 4052, 4053, 4054, 4055, 4056, 4057, 4058, 4059, 4060, 4061, 4062, 4063, 4064, 4065, 4066, 4067, 4068, 4069, 4070, 4071, 4072, 4073, 4074, 4075, 4076, 4077, 4078, 4079, 4080, 4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089, 4090, 4091, 4092, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110, 4111, 4112, 4113, 4114, 4115, 4116, 4117, 4118, 4119, 4120, 4121, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 4130, 4131, 4132, 4133, 4134, 4135, 4136, 4137, 4138, 4139, 4140, 4141, 4142, 4143, 4144, 4145, 4146, 4147, 4148, 4149, 4150, 4151, 4152, 4153, 4154, 4155, 4156, 4157, 4158, 4159, 4160, 4161, 4162, 4163, 4164, 4165, 4166, 4167, 4168, 4169, 4170, 4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179, 4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191, 4192, 4193, 4194, 4195, 4196, 4197, 4198, 4199, 4200, 4201, 4202, 4203, 4204, 4205, 4206, 4207, 4208, 4209, 4210, 4211, 4212, 4213, 4214, 4215, 4216, 4217, 4218, 4219, 4220, 4221, 4222, 4223, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4232, 4233, 4234, 4235, 4236, 4237, 4238, 4239, 4240, 4241, 4242, 4243, 4244, 4245, 4246, 4247, 4248, 4249, 4250, 4251, 4252, 4253, 4254, 4255, 4256, 4257, 4258, 4259, 4260, 4261, 4262, 4263, 4264, 4265, 4266, 4267, 4268, 4269, 4270, 4271, 4272, 4273, 4274, 4275, 4276, 4277, 4278, 4279, 4280, 4281, 4282, 4283, 4284, 4285, 4286, 4287, 4288, 4289, 4290, 4291, 4292, 4293, 4294, 4295, 4296, 4297, 4298, 4299, 4300, 4301, 4302, 4303, 4304, 4305, 4306, 4307, 4308, 4309, 4310, 4311, 4312, 4313, 4314, 4315, 4316, 4317, 4318, 4319, 4320);
DELETE FROM users WHERE id IN (882, 883, 884, 885, 886, 887, 888, 889, 890, 891, 892, 893, 894, 895, 896, 897, 898, 899, 900, 901, 902, 903, 904, 905, 906, 907);

COMMIT;
