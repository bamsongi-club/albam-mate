\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-2cb4ca9eee51'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (804, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-host@example.invalid'),
    (805, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-cancel-0@example.invalid'),
    (806, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-cancel-1@example.invalid'),
    (807, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-cancel-2@example.invalid'),
    (808, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-cancel-3@example.invalid'),
    (809, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-waiter-0@example.invalid'),
    (810, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-waiter-1@example.invalid'),
    (811, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-waiter-2@example.invalid'),
    (812, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-waiter-3@example.invalid'),
    (813, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-mixed-waiter-4@example.invalid'),
    (814, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s4-host@example.invalid'),
    (815, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s4-cancel@example.invalid'),
    (816, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s4-waiter-0@example.invalid'),
    (817, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s4-waiter-1@example.invalid'),
    (818, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s5-host@example.invalid'),
    (819, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s5-cancel@example.invalid'),
    (820, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s5-waiter-0@example.invalid'),
    (821, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s5-waiter-1@example.invalid'),
    (822, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s6-host@example.invalid'),
    (823, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s6-cancel@example.invalid'),
    (824, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s6-waiter-0@example.invalid'),
    (825, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s6-waiter-1@example.invalid'),
    (826, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s7-host@example.invalid'),
    (827, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s7-cancel@example.invalid'),
    (828, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s7-waiter-0@example.invalid'),
    (829, 'room-k6.room-k6-t1-2cb4ca9eee51.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (3121, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r0-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3122, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r0-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3123, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r0-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3124, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r0-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3125, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r0-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3126, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r1-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3127, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r1-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3128, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r1-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3129, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r1-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3130, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r1-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3131, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r2-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3132, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r2-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3133, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r2-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3134, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r2-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3135, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r2-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3136, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r3-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3137, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r3-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3138, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r3-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3139, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r3-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3140, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r3-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3141, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r4-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3142, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r4-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3143, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r4-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3144, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r4-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3145, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r4-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3146, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r5-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3147, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r5-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3148, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r5-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3149, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r5-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3150, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r5-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3151, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r6-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3152, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r6-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3153, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r6-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3154, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r6-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3155, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r6-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3156, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r7-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3157, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r7-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3158, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r7-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3159, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r7-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3160, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r7-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3161, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r8-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3162, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r8-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3163, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r8-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3164, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r8-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3165, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r8-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3166, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r9-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3167, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r9-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3168, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r9-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3169, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r9-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3170, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r9-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3171, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r10-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3172, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r10-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3173, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r10-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3174, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r10-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3175, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r10-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3176, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r11-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3177, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r11-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3178, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r11-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3179, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r11-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3180, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r11-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3181, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r12-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3182, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r12-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3183, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r12-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3184, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r12-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3185, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r12-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3186, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r13-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3187, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r13-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3188, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r13-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3189, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r13-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3190, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r13-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3191, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r14-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3192, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r14-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3193, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r14-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3194, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r14-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3195, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r14-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3196, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r15-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3197, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r15-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3198, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r15-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3199, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r15-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3200, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r15-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3201, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r16-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3202, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r16-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3203, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r16-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3204, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r16-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3205, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r16-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3206, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r17-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3207, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r17-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3208, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r17-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3209, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r17-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3210, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r17-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3211, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r18-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3212, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r18-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3213, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r18-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3214, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r18-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3215, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r18-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3216, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r19-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3217, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r19-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3218, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r19-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3219, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r19-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3220, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r19-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3221, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r20-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3222, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r20-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3223, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r20-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3224, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r20-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3225, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r20-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3226, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r21-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3227, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r21-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3228, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r21-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3229, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r21-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3230, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r21-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3231, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r22-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3232, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r22-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3233, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r22-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3234, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r22-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3235, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r22-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3236, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r23-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3237, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r23-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3238, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r23-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3239, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r23-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3240, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r23-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3241, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r24-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3242, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r24-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3243, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r24-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3244, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r24-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3245, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r24-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3246, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r25-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3247, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r25-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3248, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r25-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3249, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r25-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3250, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r25-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3251, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r26-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3252, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r26-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3253, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r26-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3254, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r26-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3255, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r26-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3256, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r27-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3257, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r27-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3258, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r27-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3259, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r27-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3260, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r27-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3261, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r28-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3262, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r28-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3263, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r28-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3264, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r28-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3265, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r28-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3266, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r29-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3267, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r29-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3268, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r29-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3269, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r29-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3270, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r29-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3271, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r30-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3272, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r30-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3273, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r30-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3274, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r30-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3275, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r30-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3276, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r31-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3277, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r31-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3278, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r31-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3279, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r31-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3280, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r31-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3281, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r32-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3282, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r32-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3283, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r32-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3284, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r32-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3285, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r32-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3286, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r33-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3287, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r33-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3288, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r33-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3289, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r33-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3290, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r33-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3291, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r34-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3292, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r34-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3293, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r34-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3294, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r34-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3295, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r34-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3296, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r35-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3297, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r35-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3298, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r35-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3299, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r35-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3300, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r35-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3301, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r36-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3302, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r36-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3303, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r36-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3304, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r36-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3305, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r36-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3306, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r37-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3307, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r37-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3308, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r37-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3309, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r37-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3310, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r37-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3311, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r38-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3312, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r38-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3313, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r38-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3314, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r38-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3315, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r38-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3316, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r39-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3317, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r39-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3318, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r39-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3319, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r39-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3320, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r39-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3321, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r40-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3322, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r40-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3323, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r40-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3324, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r40-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3325, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r40-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3326, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r41-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3327, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r41-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3328, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r41-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3329, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r41-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3330, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r41-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3331, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r42-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3332, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r42-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3333, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r42-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3334, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r42-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3335, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r42-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3336, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r43-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3337, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r43-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3338, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r43-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3339, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r43-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3340, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r43-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3341, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r44-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3342, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r44-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3343, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r44-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3344, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r44-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3345, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r44-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3346, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r45-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3347, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r45-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3348, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r45-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3349, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r45-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3350, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r45-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3351, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r46-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3352, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r46-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3353, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r46-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3354, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r46-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3355, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r46-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3356, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r47-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3357, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r47-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3358, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r47-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3359, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r47-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3360, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r47-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3361, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r48-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3362, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r48-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3363, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r48-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3364, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r48-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3365, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r48-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3366, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r49-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3367, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r49-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3368, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r49-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3369, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r49-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3370, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r49-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3371, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r50-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3372, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r50-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3373, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r50-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3374, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r50-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3375, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r50-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3376, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r51-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3377, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r51-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3378, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r51-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3379, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r51-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3380, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r51-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3381, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r52-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3382, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r52-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3383, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r52-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3384, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r52-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3385, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r52-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3386, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r53-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3387, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r53-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3388, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r53-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3389, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r53-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3390, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r53-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3391, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r54-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3392, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r54-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3393, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r54-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3394, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r54-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3395, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r54-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3396, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r55-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3397, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r55-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3398, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r55-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3399, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r55-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3400, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r55-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3401, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r56-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3402, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r56-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3403, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r56-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3404, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r56-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3405, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r56-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3406, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r57-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3407, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r57-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3408, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r57-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3409, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r57-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3410, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r57-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3411, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r58-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3412, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r58-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3413, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r58-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3414, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r58-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3415, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r58-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3416, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r59-mixed-hot', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3417, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r59-spread-s4', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3418, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r59-spread-s5', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3419, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r59-spread-s6', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8'),
    (3420, 'ROOM-K6 room-k6-t1-2cb4ca9eee51 t1-r59-spread-s7', 'ROOM k6 fixture 5703318804d14cdb9becf82f897d2bb8');

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

DELETE FROM notifications WHERE room_id IN (3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128, 3129, 3130, 3131, 3132, 3133, 3134, 3135, 3136, 3137, 3138, 3139, 3140, 3141, 3142, 3143, 3144, 3145, 3146, 3147, 3148, 3149, 3150, 3151, 3152, 3153, 3154, 3155, 3156, 3157, 3158, 3159, 3160, 3161, 3162, 3163, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3172, 3173, 3174, 3175, 3176, 3177, 3178, 3179, 3180, 3181, 3182, 3183, 3184, 3185, 3186, 3187, 3188, 3189, 3190, 3191, 3192, 3193, 3194, 3195, 3196, 3197, 3198, 3199, 3200, 3201, 3202, 3203, 3204, 3205, 3206, 3207, 3208, 3209, 3210, 3211, 3212, 3213, 3214, 3215, 3216, 3217, 3218, 3219, 3220, 3221, 3222, 3223, 3224, 3225, 3226, 3227, 3228, 3229, 3230, 3231, 3232, 3233, 3234, 3235, 3236, 3237, 3238, 3239, 3240, 3241, 3242, 3243, 3244, 3245, 3246, 3247, 3248, 3249, 3250, 3251, 3252, 3253, 3254, 3255, 3256, 3257, 3258, 3259, 3260, 3261, 3262, 3263, 3264, 3265, 3266, 3267, 3268, 3269, 3270, 3271, 3272, 3273, 3274, 3275, 3276, 3277, 3278, 3279, 3280, 3281, 3282, 3283, 3284, 3285, 3286, 3287, 3288, 3289, 3290, 3291, 3292, 3293, 3294, 3295, 3296, 3297, 3298, 3299, 3300, 3301, 3302, 3303, 3304, 3305, 3306, 3307, 3308, 3309, 3310, 3311, 3312, 3313, 3314, 3315, 3316, 3317, 3318, 3319, 3320, 3321, 3322, 3323, 3324, 3325, 3326, 3327, 3328, 3329, 3330, 3331, 3332, 3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340, 3341, 3342, 3343, 3344, 3345, 3346, 3347, 3348, 3349, 3350, 3351, 3352, 3353, 3354, 3355, 3356, 3357, 3358, 3359, 3360, 3361, 3362, 3363, 3364, 3365, 3366, 3367, 3368, 3369, 3370, 3371, 3372, 3373, 3374, 3375, 3376, 3377, 3378, 3379, 3380, 3381, 3382, 3383, 3384, 3385, 3386, 3387, 3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395, 3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403, 3404, 3405, 3406, 3407, 3408, 3409, 3410, 3411, 3412, 3413, 3414, 3415, 3416, 3417, 3418, 3419, 3420);
DELETE FROM notification_outbox_events WHERE room_id IN (3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128, 3129, 3130, 3131, 3132, 3133, 3134, 3135, 3136, 3137, 3138, 3139, 3140, 3141, 3142, 3143, 3144, 3145, 3146, 3147, 3148, 3149, 3150, 3151, 3152, 3153, 3154, 3155, 3156, 3157, 3158, 3159, 3160, 3161, 3162, 3163, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3172, 3173, 3174, 3175, 3176, 3177, 3178, 3179, 3180, 3181, 3182, 3183, 3184, 3185, 3186, 3187, 3188, 3189, 3190, 3191, 3192, 3193, 3194, 3195, 3196, 3197, 3198, 3199, 3200, 3201, 3202, 3203, 3204, 3205, 3206, 3207, 3208, 3209, 3210, 3211, 3212, 3213, 3214, 3215, 3216, 3217, 3218, 3219, 3220, 3221, 3222, 3223, 3224, 3225, 3226, 3227, 3228, 3229, 3230, 3231, 3232, 3233, 3234, 3235, 3236, 3237, 3238, 3239, 3240, 3241, 3242, 3243, 3244, 3245, 3246, 3247, 3248, 3249, 3250, 3251, 3252, 3253, 3254, 3255, 3256, 3257, 3258, 3259, 3260, 3261, 3262, 3263, 3264, 3265, 3266, 3267, 3268, 3269, 3270, 3271, 3272, 3273, 3274, 3275, 3276, 3277, 3278, 3279, 3280, 3281, 3282, 3283, 3284, 3285, 3286, 3287, 3288, 3289, 3290, 3291, 3292, 3293, 3294, 3295, 3296, 3297, 3298, 3299, 3300, 3301, 3302, 3303, 3304, 3305, 3306, 3307, 3308, 3309, 3310, 3311, 3312, 3313, 3314, 3315, 3316, 3317, 3318, 3319, 3320, 3321, 3322, 3323, 3324, 3325, 3326, 3327, 3328, 3329, 3330, 3331, 3332, 3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340, 3341, 3342, 3343, 3344, 3345, 3346, 3347, 3348, 3349, 3350, 3351, 3352, 3353, 3354, 3355, 3356, 3357, 3358, 3359, 3360, 3361, 3362, 3363, 3364, 3365, 3366, 3367, 3368, 3369, 3370, 3371, 3372, 3373, 3374, 3375, 3376, 3377, 3378, 3379, 3380, 3381, 3382, 3383, 3384, 3385, 3386, 3387, 3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395, 3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403, 3404, 3405, 3406, 3407, 3408, 3409, 3410, 3411, 3412, 3413, 3414, 3415, 3416, 3417, 3418, 3419, 3420);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128, 3129, 3130, 3131, 3132, 3133, 3134, 3135, 3136, 3137, 3138, 3139, 3140, 3141, 3142, 3143, 3144, 3145, 3146, 3147, 3148, 3149, 3150, 3151, 3152, 3153, 3154, 3155, 3156, 3157, 3158, 3159, 3160, 3161, 3162, 3163, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3172, 3173, 3174, 3175, 3176, 3177, 3178, 3179, 3180, 3181, 3182, 3183, 3184, 3185, 3186, 3187, 3188, 3189, 3190, 3191, 3192, 3193, 3194, 3195, 3196, 3197, 3198, 3199, 3200, 3201, 3202, 3203, 3204, 3205, 3206, 3207, 3208, 3209, 3210, 3211, 3212, 3213, 3214, 3215, 3216, 3217, 3218, 3219, 3220, 3221, 3222, 3223, 3224, 3225, 3226, 3227, 3228, 3229, 3230, 3231, 3232, 3233, 3234, 3235, 3236, 3237, 3238, 3239, 3240, 3241, 3242, 3243, 3244, 3245, 3246, 3247, 3248, 3249, 3250, 3251, 3252, 3253, 3254, 3255, 3256, 3257, 3258, 3259, 3260, 3261, 3262, 3263, 3264, 3265, 3266, 3267, 3268, 3269, 3270, 3271, 3272, 3273, 3274, 3275, 3276, 3277, 3278, 3279, 3280, 3281, 3282, 3283, 3284, 3285, 3286, 3287, 3288, 3289, 3290, 3291, 3292, 3293, 3294, 3295, 3296, 3297, 3298, 3299, 3300, 3301, 3302, 3303, 3304, 3305, 3306, 3307, 3308, 3309, 3310, 3311, 3312, 3313, 3314, 3315, 3316, 3317, 3318, 3319, 3320, 3321, 3322, 3323, 3324, 3325, 3326, 3327, 3328, 3329, 3330, 3331, 3332, 3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340, 3341, 3342, 3343, 3344, 3345, 3346, 3347, 3348, 3349, 3350, 3351, 3352, 3353, 3354, 3355, 3356, 3357, 3358, 3359, 3360, 3361, 3362, 3363, 3364, 3365, 3366, 3367, 3368, 3369, 3370, 3371, 3372, 3373, 3374, 3375, 3376, 3377, 3378, 3379, 3380, 3381, 3382, 3383, 3384, 3385, 3386, 3387, 3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395, 3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403, 3404, 3405, 3406, 3407, 3408, 3409, 3410, 3411, 3412, 3413, 3414, 3415, 3416, 3417, 3418, 3419, 3420)
);
DELETE FROM chat_rooms WHERE room_id IN (3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128, 3129, 3130, 3131, 3132, 3133, 3134, 3135, 3136, 3137, 3138, 3139, 3140, 3141, 3142, 3143, 3144, 3145, 3146, 3147, 3148, 3149, 3150, 3151, 3152, 3153, 3154, 3155, 3156, 3157, 3158, 3159, 3160, 3161, 3162, 3163, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3172, 3173, 3174, 3175, 3176, 3177, 3178, 3179, 3180, 3181, 3182, 3183, 3184, 3185, 3186, 3187, 3188, 3189, 3190, 3191, 3192, 3193, 3194, 3195, 3196, 3197, 3198, 3199, 3200, 3201, 3202, 3203, 3204, 3205, 3206, 3207, 3208, 3209, 3210, 3211, 3212, 3213, 3214, 3215, 3216, 3217, 3218, 3219, 3220, 3221, 3222, 3223, 3224, 3225, 3226, 3227, 3228, 3229, 3230, 3231, 3232, 3233, 3234, 3235, 3236, 3237, 3238, 3239, 3240, 3241, 3242, 3243, 3244, 3245, 3246, 3247, 3248, 3249, 3250, 3251, 3252, 3253, 3254, 3255, 3256, 3257, 3258, 3259, 3260, 3261, 3262, 3263, 3264, 3265, 3266, 3267, 3268, 3269, 3270, 3271, 3272, 3273, 3274, 3275, 3276, 3277, 3278, 3279, 3280, 3281, 3282, 3283, 3284, 3285, 3286, 3287, 3288, 3289, 3290, 3291, 3292, 3293, 3294, 3295, 3296, 3297, 3298, 3299, 3300, 3301, 3302, 3303, 3304, 3305, 3306, 3307, 3308, 3309, 3310, 3311, 3312, 3313, 3314, 3315, 3316, 3317, 3318, 3319, 3320, 3321, 3322, 3323, 3324, 3325, 3326, 3327, 3328, 3329, 3330, 3331, 3332, 3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340, 3341, 3342, 3343, 3344, 3345, 3346, 3347, 3348, 3349, 3350, 3351, 3352, 3353, 3354, 3355, 3356, 3357, 3358, 3359, 3360, 3361, 3362, 3363, 3364, 3365, 3366, 3367, 3368, 3369, 3370, 3371, 3372, 3373, 3374, 3375, 3376, 3377, 3378, 3379, 3380, 3381, 3382, 3383, 3384, 3385, 3386, 3387, 3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395, 3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403, 3404, 3405, 3406, 3407, 3408, 3409, 3410, 3411, 3412, 3413, 3414, 3415, 3416, 3417, 3418, 3419, 3420);
DELETE FROM room_waitlists WHERE room_id IN (3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128, 3129, 3130, 3131, 3132, 3133, 3134, 3135, 3136, 3137, 3138, 3139, 3140, 3141, 3142, 3143, 3144, 3145, 3146, 3147, 3148, 3149, 3150, 3151, 3152, 3153, 3154, 3155, 3156, 3157, 3158, 3159, 3160, 3161, 3162, 3163, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3172, 3173, 3174, 3175, 3176, 3177, 3178, 3179, 3180, 3181, 3182, 3183, 3184, 3185, 3186, 3187, 3188, 3189, 3190, 3191, 3192, 3193, 3194, 3195, 3196, 3197, 3198, 3199, 3200, 3201, 3202, 3203, 3204, 3205, 3206, 3207, 3208, 3209, 3210, 3211, 3212, 3213, 3214, 3215, 3216, 3217, 3218, 3219, 3220, 3221, 3222, 3223, 3224, 3225, 3226, 3227, 3228, 3229, 3230, 3231, 3232, 3233, 3234, 3235, 3236, 3237, 3238, 3239, 3240, 3241, 3242, 3243, 3244, 3245, 3246, 3247, 3248, 3249, 3250, 3251, 3252, 3253, 3254, 3255, 3256, 3257, 3258, 3259, 3260, 3261, 3262, 3263, 3264, 3265, 3266, 3267, 3268, 3269, 3270, 3271, 3272, 3273, 3274, 3275, 3276, 3277, 3278, 3279, 3280, 3281, 3282, 3283, 3284, 3285, 3286, 3287, 3288, 3289, 3290, 3291, 3292, 3293, 3294, 3295, 3296, 3297, 3298, 3299, 3300, 3301, 3302, 3303, 3304, 3305, 3306, 3307, 3308, 3309, 3310, 3311, 3312, 3313, 3314, 3315, 3316, 3317, 3318, 3319, 3320, 3321, 3322, 3323, 3324, 3325, 3326, 3327, 3328, 3329, 3330, 3331, 3332, 3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340, 3341, 3342, 3343, 3344, 3345, 3346, 3347, 3348, 3349, 3350, 3351, 3352, 3353, 3354, 3355, 3356, 3357, 3358, 3359, 3360, 3361, 3362, 3363, 3364, 3365, 3366, 3367, 3368, 3369, 3370, 3371, 3372, 3373, 3374, 3375, 3376, 3377, 3378, 3379, 3380, 3381, 3382, 3383, 3384, 3385, 3386, 3387, 3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395, 3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403, 3404, 3405, 3406, 3407, 3408, 3409, 3410, 3411, 3412, 3413, 3414, 3415, 3416, 3417, 3418, 3419, 3420);
DELETE FROM participations WHERE room_id IN (3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128, 3129, 3130, 3131, 3132, 3133, 3134, 3135, 3136, 3137, 3138, 3139, 3140, 3141, 3142, 3143, 3144, 3145, 3146, 3147, 3148, 3149, 3150, 3151, 3152, 3153, 3154, 3155, 3156, 3157, 3158, 3159, 3160, 3161, 3162, 3163, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3172, 3173, 3174, 3175, 3176, 3177, 3178, 3179, 3180, 3181, 3182, 3183, 3184, 3185, 3186, 3187, 3188, 3189, 3190, 3191, 3192, 3193, 3194, 3195, 3196, 3197, 3198, 3199, 3200, 3201, 3202, 3203, 3204, 3205, 3206, 3207, 3208, 3209, 3210, 3211, 3212, 3213, 3214, 3215, 3216, 3217, 3218, 3219, 3220, 3221, 3222, 3223, 3224, 3225, 3226, 3227, 3228, 3229, 3230, 3231, 3232, 3233, 3234, 3235, 3236, 3237, 3238, 3239, 3240, 3241, 3242, 3243, 3244, 3245, 3246, 3247, 3248, 3249, 3250, 3251, 3252, 3253, 3254, 3255, 3256, 3257, 3258, 3259, 3260, 3261, 3262, 3263, 3264, 3265, 3266, 3267, 3268, 3269, 3270, 3271, 3272, 3273, 3274, 3275, 3276, 3277, 3278, 3279, 3280, 3281, 3282, 3283, 3284, 3285, 3286, 3287, 3288, 3289, 3290, 3291, 3292, 3293, 3294, 3295, 3296, 3297, 3298, 3299, 3300, 3301, 3302, 3303, 3304, 3305, 3306, 3307, 3308, 3309, 3310, 3311, 3312, 3313, 3314, 3315, 3316, 3317, 3318, 3319, 3320, 3321, 3322, 3323, 3324, 3325, 3326, 3327, 3328, 3329, 3330, 3331, 3332, 3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340, 3341, 3342, 3343, 3344, 3345, 3346, 3347, 3348, 3349, 3350, 3351, 3352, 3353, 3354, 3355, 3356, 3357, 3358, 3359, 3360, 3361, 3362, 3363, 3364, 3365, 3366, 3367, 3368, 3369, 3370, 3371, 3372, 3373, 3374, 3375, 3376, 3377, 3378, 3379, 3380, 3381, 3382, 3383, 3384, 3385, 3386, 3387, 3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395, 3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403, 3404, 3405, 3406, 3407, 3408, 3409, 3410, 3411, 3412, 3413, 3414, 3415, 3416, 3417, 3418, 3419, 3420);
DELETE FROM rooms WHERE id IN (3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128, 3129, 3130, 3131, 3132, 3133, 3134, 3135, 3136, 3137, 3138, 3139, 3140, 3141, 3142, 3143, 3144, 3145, 3146, 3147, 3148, 3149, 3150, 3151, 3152, 3153, 3154, 3155, 3156, 3157, 3158, 3159, 3160, 3161, 3162, 3163, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3172, 3173, 3174, 3175, 3176, 3177, 3178, 3179, 3180, 3181, 3182, 3183, 3184, 3185, 3186, 3187, 3188, 3189, 3190, 3191, 3192, 3193, 3194, 3195, 3196, 3197, 3198, 3199, 3200, 3201, 3202, 3203, 3204, 3205, 3206, 3207, 3208, 3209, 3210, 3211, 3212, 3213, 3214, 3215, 3216, 3217, 3218, 3219, 3220, 3221, 3222, 3223, 3224, 3225, 3226, 3227, 3228, 3229, 3230, 3231, 3232, 3233, 3234, 3235, 3236, 3237, 3238, 3239, 3240, 3241, 3242, 3243, 3244, 3245, 3246, 3247, 3248, 3249, 3250, 3251, 3252, 3253, 3254, 3255, 3256, 3257, 3258, 3259, 3260, 3261, 3262, 3263, 3264, 3265, 3266, 3267, 3268, 3269, 3270, 3271, 3272, 3273, 3274, 3275, 3276, 3277, 3278, 3279, 3280, 3281, 3282, 3283, 3284, 3285, 3286, 3287, 3288, 3289, 3290, 3291, 3292, 3293, 3294, 3295, 3296, 3297, 3298, 3299, 3300, 3301, 3302, 3303, 3304, 3305, 3306, 3307, 3308, 3309, 3310, 3311, 3312, 3313, 3314, 3315, 3316, 3317, 3318, 3319, 3320, 3321, 3322, 3323, 3324, 3325, 3326, 3327, 3328, 3329, 3330, 3331, 3332, 3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340, 3341, 3342, 3343, 3344, 3345, 3346, 3347, 3348, 3349, 3350, 3351, 3352, 3353, 3354, 3355, 3356, 3357, 3358, 3359, 3360, 3361, 3362, 3363, 3364, 3365, 3366, 3367, 3368, 3369, 3370, 3371, 3372, 3373, 3374, 3375, 3376, 3377, 3378, 3379, 3380, 3381, 3382, 3383, 3384, 3385, 3386, 3387, 3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395, 3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403, 3404, 3405, 3406, 3407, 3408, 3409, 3410, 3411, 3412, 3413, 3414, 3415, 3416, 3417, 3418, 3419, 3420);
DELETE FROM users WHERE id IN (804, 805, 806, 807, 808, 809, 810, 811, 812, 813, 814, 815, 816, 817, 818, 819, 820, 821, 822, 823, 824, 825, 826, 827, 828, 829);

COMMIT;
