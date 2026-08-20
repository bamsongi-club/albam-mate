\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-2d4b211eb513'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (830, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-host@example.invalid'),
    (831, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-cancel-0@example.invalid'),
    (832, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-cancel-1@example.invalid'),
    (833, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-cancel-2@example.invalid'),
    (834, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-cancel-3@example.invalid'),
    (835, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-waiter-0@example.invalid'),
    (836, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-waiter-1@example.invalid'),
    (837, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-waiter-2@example.invalid'),
    (838, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-waiter-3@example.invalid'),
    (839, 'room-k6.room-k6-t1-2d4b211eb513.t1-mixed-waiter-4@example.invalid'),
    (840, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s4-host@example.invalid'),
    (841, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s4-cancel@example.invalid'),
    (842, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s4-waiter-0@example.invalid'),
    (843, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s4-waiter-1@example.invalid'),
    (844, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s5-host@example.invalid'),
    (845, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s5-cancel@example.invalid'),
    (846, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s5-waiter-0@example.invalid'),
    (847, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s5-waiter-1@example.invalid'),
    (848, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s6-host@example.invalid'),
    (849, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s6-cancel@example.invalid'),
    (850, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s6-waiter-0@example.invalid'),
    (851, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s6-waiter-1@example.invalid'),
    (852, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s7-host@example.invalid'),
    (853, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s7-cancel@example.invalid'),
    (854, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s7-waiter-0@example.invalid'),
    (855, 'room-k6.room-k6-t1-2d4b211eb513.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (3421, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r0-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3422, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r0-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3423, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r0-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3424, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r0-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3425, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r0-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3426, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r1-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3427, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r1-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3428, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r1-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3429, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r1-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3430, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r1-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3431, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r2-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3432, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r2-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3433, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r2-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3434, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r2-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3435, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r2-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3436, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r3-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3437, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r3-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3438, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r3-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3439, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r3-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3440, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r3-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3441, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r4-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3442, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r4-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3443, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r4-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3444, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r4-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3445, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r4-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3446, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r5-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3447, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r5-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3448, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r5-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3449, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r5-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3450, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r5-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3451, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r6-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3452, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r6-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3453, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r6-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3454, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r6-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3455, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r6-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3456, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r7-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3457, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r7-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3458, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r7-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3459, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r7-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3460, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r7-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3461, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r8-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3462, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r8-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3463, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r8-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3464, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r8-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3465, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r8-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3466, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r9-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3467, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r9-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3468, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r9-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3469, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r9-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3470, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r9-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3471, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r10-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3472, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r10-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3473, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r10-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3474, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r10-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3475, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r10-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3476, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r11-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3477, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r11-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3478, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r11-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3479, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r11-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3480, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r11-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3481, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r12-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3482, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r12-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3483, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r12-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3484, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r12-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3485, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r12-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3486, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r13-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3487, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r13-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3488, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r13-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3489, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r13-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3490, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r13-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3491, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r14-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3492, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r14-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3493, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r14-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3494, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r14-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3495, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r14-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3496, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r15-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3497, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r15-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3498, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r15-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3499, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r15-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3500, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r15-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3501, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r16-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3502, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r16-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3503, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r16-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3504, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r16-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3505, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r16-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3506, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r17-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3507, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r17-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3508, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r17-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3509, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r17-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3510, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r17-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3511, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r18-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3512, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r18-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3513, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r18-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3514, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r18-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3515, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r18-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3516, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r19-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3517, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r19-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3518, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r19-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3519, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r19-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3520, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r19-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3521, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r20-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3522, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r20-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3523, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r20-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3524, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r20-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3525, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r20-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3526, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r21-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3527, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r21-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3528, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r21-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3529, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r21-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3530, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r21-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3531, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r22-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3532, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r22-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3533, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r22-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3534, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r22-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3535, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r22-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3536, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r23-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3537, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r23-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3538, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r23-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3539, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r23-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3540, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r23-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3541, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r24-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3542, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r24-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3543, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r24-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3544, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r24-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3545, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r24-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3546, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r25-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3547, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r25-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3548, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r25-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3549, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r25-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3550, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r25-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3551, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r26-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3552, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r26-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3553, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r26-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3554, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r26-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3555, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r26-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3556, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r27-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3557, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r27-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3558, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r27-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3559, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r27-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3560, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r27-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3561, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r28-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3562, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r28-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3563, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r28-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3564, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r28-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3565, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r28-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3566, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r29-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3567, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r29-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3568, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r29-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3569, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r29-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3570, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r29-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3571, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r30-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3572, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r30-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3573, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r30-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3574, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r30-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3575, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r30-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3576, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r31-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3577, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r31-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3578, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r31-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3579, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r31-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3580, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r31-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3581, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r32-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3582, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r32-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3583, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r32-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3584, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r32-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3585, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r32-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3586, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r33-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3587, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r33-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3588, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r33-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3589, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r33-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3590, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r33-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3591, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r34-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3592, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r34-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3593, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r34-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3594, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r34-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3595, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r34-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3596, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r35-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3597, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r35-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3598, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r35-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3599, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r35-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3600, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r35-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3601, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r36-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3602, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r36-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3603, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r36-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3604, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r36-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3605, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r36-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3606, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r37-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3607, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r37-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3608, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r37-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3609, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r37-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3610, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r37-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3611, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r38-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3612, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r38-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3613, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r38-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3614, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r38-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3615, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r38-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3616, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r39-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3617, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r39-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3618, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r39-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3619, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r39-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3620, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r39-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3621, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r40-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3622, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r40-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3623, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r40-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3624, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r40-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3625, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r40-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3626, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r41-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3627, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r41-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3628, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r41-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3629, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r41-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3630, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r41-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3631, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r42-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3632, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r42-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3633, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r42-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3634, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r42-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3635, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r42-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3636, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r43-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3637, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r43-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3638, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r43-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3639, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r43-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3640, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r43-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3641, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r44-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3642, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r44-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3643, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r44-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3644, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r44-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3645, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r44-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3646, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r45-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3647, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r45-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3648, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r45-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3649, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r45-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3650, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r45-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3651, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r46-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3652, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r46-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3653, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r46-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3654, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r46-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3655, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r46-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3656, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r47-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3657, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r47-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3658, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r47-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3659, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r47-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3660, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r47-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3661, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r48-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3662, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r48-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3663, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r48-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3664, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r48-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3665, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r48-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3666, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r49-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3667, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r49-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3668, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r49-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3669, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r49-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3670, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r49-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3671, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r50-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3672, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r50-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3673, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r50-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3674, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r50-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3675, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r50-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3676, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r51-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3677, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r51-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3678, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r51-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3679, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r51-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3680, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r51-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3681, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r52-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3682, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r52-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3683, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r52-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3684, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r52-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3685, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r52-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3686, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r53-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3687, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r53-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3688, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r53-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3689, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r53-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3690, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r53-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3691, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r54-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3692, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r54-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3693, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r54-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3694, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r54-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3695, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r54-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3696, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r55-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3697, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r55-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3698, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r55-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3699, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r55-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3700, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r55-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3701, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r56-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3702, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r56-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3703, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r56-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3704, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r56-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3705, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r56-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3706, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r57-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3707, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r57-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3708, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r57-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3709, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r57-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3710, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r57-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3711, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r58-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3712, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r58-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3713, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r58-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3714, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r58-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3715, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r58-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3716, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r59-mixed-hot', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3717, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r59-spread-s4', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3718, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r59-spread-s5', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3719, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r59-spread-s6', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905'),
    (3720, 'ROOM-K6 room-k6-t1-2d4b211eb513 t1-r59-spread-s7', 'ROOM k6 fixture 10f71bd9ca274ed2aef7c7d6cebb5905');

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

DELETE FROM notifications WHERE room_id IN (3421, 3422, 3423, 3424, 3425, 3426, 3427, 3428, 3429, 3430, 3431, 3432, 3433, 3434, 3435, 3436, 3437, 3438, 3439, 3440, 3441, 3442, 3443, 3444, 3445, 3446, 3447, 3448, 3449, 3450, 3451, 3452, 3453, 3454, 3455, 3456, 3457, 3458, 3459, 3460, 3461, 3462, 3463, 3464, 3465, 3466, 3467, 3468, 3469, 3470, 3471, 3472, 3473, 3474, 3475, 3476, 3477, 3478, 3479, 3480, 3481, 3482, 3483, 3484, 3485, 3486, 3487, 3488, 3489, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498, 3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510, 3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521, 3522, 3523, 3524, 3525, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533, 3534, 3535, 3536, 3537, 3538, 3539, 3540, 3541, 3542, 3543, 3544, 3545, 3546, 3547, 3548, 3549, 3550, 3551, 3552, 3553, 3554, 3555, 3556, 3557, 3558, 3559, 3560, 3561, 3562, 3563, 3564, 3565, 3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3576, 3577, 3578, 3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 3619, 3620, 3621, 3622, 3623, 3624, 3625, 3626, 3627, 3628, 3629, 3630, 3631, 3632, 3633, 3634, 3635, 3636, 3637, 3638, 3639, 3640, 3641, 3642, 3643, 3644, 3645, 3646, 3647, 3648, 3649, 3650, 3651, 3652, 3653, 3654, 3655, 3656, 3657, 3658, 3659, 3660, 3661, 3662, 3663, 3664, 3665, 3666, 3667, 3668, 3669, 3670, 3671, 3672, 3673, 3674, 3675, 3676, 3677, 3678, 3679, 3680, 3681, 3682, 3683, 3684, 3685, 3686, 3687, 3688, 3689, 3690, 3691, 3692, 3693, 3694, 3695, 3696, 3697, 3698, 3699, 3700, 3701, 3702, 3703, 3704, 3705, 3706, 3707, 3708, 3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719, 3720);
DELETE FROM notification_outbox_events WHERE room_id IN (3421, 3422, 3423, 3424, 3425, 3426, 3427, 3428, 3429, 3430, 3431, 3432, 3433, 3434, 3435, 3436, 3437, 3438, 3439, 3440, 3441, 3442, 3443, 3444, 3445, 3446, 3447, 3448, 3449, 3450, 3451, 3452, 3453, 3454, 3455, 3456, 3457, 3458, 3459, 3460, 3461, 3462, 3463, 3464, 3465, 3466, 3467, 3468, 3469, 3470, 3471, 3472, 3473, 3474, 3475, 3476, 3477, 3478, 3479, 3480, 3481, 3482, 3483, 3484, 3485, 3486, 3487, 3488, 3489, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498, 3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510, 3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521, 3522, 3523, 3524, 3525, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533, 3534, 3535, 3536, 3537, 3538, 3539, 3540, 3541, 3542, 3543, 3544, 3545, 3546, 3547, 3548, 3549, 3550, 3551, 3552, 3553, 3554, 3555, 3556, 3557, 3558, 3559, 3560, 3561, 3562, 3563, 3564, 3565, 3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3576, 3577, 3578, 3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 3619, 3620, 3621, 3622, 3623, 3624, 3625, 3626, 3627, 3628, 3629, 3630, 3631, 3632, 3633, 3634, 3635, 3636, 3637, 3638, 3639, 3640, 3641, 3642, 3643, 3644, 3645, 3646, 3647, 3648, 3649, 3650, 3651, 3652, 3653, 3654, 3655, 3656, 3657, 3658, 3659, 3660, 3661, 3662, 3663, 3664, 3665, 3666, 3667, 3668, 3669, 3670, 3671, 3672, 3673, 3674, 3675, 3676, 3677, 3678, 3679, 3680, 3681, 3682, 3683, 3684, 3685, 3686, 3687, 3688, 3689, 3690, 3691, 3692, 3693, 3694, 3695, 3696, 3697, 3698, 3699, 3700, 3701, 3702, 3703, 3704, 3705, 3706, 3707, 3708, 3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719, 3720);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (3421, 3422, 3423, 3424, 3425, 3426, 3427, 3428, 3429, 3430, 3431, 3432, 3433, 3434, 3435, 3436, 3437, 3438, 3439, 3440, 3441, 3442, 3443, 3444, 3445, 3446, 3447, 3448, 3449, 3450, 3451, 3452, 3453, 3454, 3455, 3456, 3457, 3458, 3459, 3460, 3461, 3462, 3463, 3464, 3465, 3466, 3467, 3468, 3469, 3470, 3471, 3472, 3473, 3474, 3475, 3476, 3477, 3478, 3479, 3480, 3481, 3482, 3483, 3484, 3485, 3486, 3487, 3488, 3489, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498, 3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510, 3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521, 3522, 3523, 3524, 3525, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533, 3534, 3535, 3536, 3537, 3538, 3539, 3540, 3541, 3542, 3543, 3544, 3545, 3546, 3547, 3548, 3549, 3550, 3551, 3552, 3553, 3554, 3555, 3556, 3557, 3558, 3559, 3560, 3561, 3562, 3563, 3564, 3565, 3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3576, 3577, 3578, 3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 3619, 3620, 3621, 3622, 3623, 3624, 3625, 3626, 3627, 3628, 3629, 3630, 3631, 3632, 3633, 3634, 3635, 3636, 3637, 3638, 3639, 3640, 3641, 3642, 3643, 3644, 3645, 3646, 3647, 3648, 3649, 3650, 3651, 3652, 3653, 3654, 3655, 3656, 3657, 3658, 3659, 3660, 3661, 3662, 3663, 3664, 3665, 3666, 3667, 3668, 3669, 3670, 3671, 3672, 3673, 3674, 3675, 3676, 3677, 3678, 3679, 3680, 3681, 3682, 3683, 3684, 3685, 3686, 3687, 3688, 3689, 3690, 3691, 3692, 3693, 3694, 3695, 3696, 3697, 3698, 3699, 3700, 3701, 3702, 3703, 3704, 3705, 3706, 3707, 3708, 3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719, 3720)
);
DELETE FROM chat_rooms WHERE room_id IN (3421, 3422, 3423, 3424, 3425, 3426, 3427, 3428, 3429, 3430, 3431, 3432, 3433, 3434, 3435, 3436, 3437, 3438, 3439, 3440, 3441, 3442, 3443, 3444, 3445, 3446, 3447, 3448, 3449, 3450, 3451, 3452, 3453, 3454, 3455, 3456, 3457, 3458, 3459, 3460, 3461, 3462, 3463, 3464, 3465, 3466, 3467, 3468, 3469, 3470, 3471, 3472, 3473, 3474, 3475, 3476, 3477, 3478, 3479, 3480, 3481, 3482, 3483, 3484, 3485, 3486, 3487, 3488, 3489, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498, 3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510, 3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521, 3522, 3523, 3524, 3525, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533, 3534, 3535, 3536, 3537, 3538, 3539, 3540, 3541, 3542, 3543, 3544, 3545, 3546, 3547, 3548, 3549, 3550, 3551, 3552, 3553, 3554, 3555, 3556, 3557, 3558, 3559, 3560, 3561, 3562, 3563, 3564, 3565, 3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3576, 3577, 3578, 3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 3619, 3620, 3621, 3622, 3623, 3624, 3625, 3626, 3627, 3628, 3629, 3630, 3631, 3632, 3633, 3634, 3635, 3636, 3637, 3638, 3639, 3640, 3641, 3642, 3643, 3644, 3645, 3646, 3647, 3648, 3649, 3650, 3651, 3652, 3653, 3654, 3655, 3656, 3657, 3658, 3659, 3660, 3661, 3662, 3663, 3664, 3665, 3666, 3667, 3668, 3669, 3670, 3671, 3672, 3673, 3674, 3675, 3676, 3677, 3678, 3679, 3680, 3681, 3682, 3683, 3684, 3685, 3686, 3687, 3688, 3689, 3690, 3691, 3692, 3693, 3694, 3695, 3696, 3697, 3698, 3699, 3700, 3701, 3702, 3703, 3704, 3705, 3706, 3707, 3708, 3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719, 3720);
DELETE FROM room_waitlists WHERE room_id IN (3421, 3422, 3423, 3424, 3425, 3426, 3427, 3428, 3429, 3430, 3431, 3432, 3433, 3434, 3435, 3436, 3437, 3438, 3439, 3440, 3441, 3442, 3443, 3444, 3445, 3446, 3447, 3448, 3449, 3450, 3451, 3452, 3453, 3454, 3455, 3456, 3457, 3458, 3459, 3460, 3461, 3462, 3463, 3464, 3465, 3466, 3467, 3468, 3469, 3470, 3471, 3472, 3473, 3474, 3475, 3476, 3477, 3478, 3479, 3480, 3481, 3482, 3483, 3484, 3485, 3486, 3487, 3488, 3489, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498, 3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510, 3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521, 3522, 3523, 3524, 3525, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533, 3534, 3535, 3536, 3537, 3538, 3539, 3540, 3541, 3542, 3543, 3544, 3545, 3546, 3547, 3548, 3549, 3550, 3551, 3552, 3553, 3554, 3555, 3556, 3557, 3558, 3559, 3560, 3561, 3562, 3563, 3564, 3565, 3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3576, 3577, 3578, 3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 3619, 3620, 3621, 3622, 3623, 3624, 3625, 3626, 3627, 3628, 3629, 3630, 3631, 3632, 3633, 3634, 3635, 3636, 3637, 3638, 3639, 3640, 3641, 3642, 3643, 3644, 3645, 3646, 3647, 3648, 3649, 3650, 3651, 3652, 3653, 3654, 3655, 3656, 3657, 3658, 3659, 3660, 3661, 3662, 3663, 3664, 3665, 3666, 3667, 3668, 3669, 3670, 3671, 3672, 3673, 3674, 3675, 3676, 3677, 3678, 3679, 3680, 3681, 3682, 3683, 3684, 3685, 3686, 3687, 3688, 3689, 3690, 3691, 3692, 3693, 3694, 3695, 3696, 3697, 3698, 3699, 3700, 3701, 3702, 3703, 3704, 3705, 3706, 3707, 3708, 3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719, 3720);
DELETE FROM participations WHERE room_id IN (3421, 3422, 3423, 3424, 3425, 3426, 3427, 3428, 3429, 3430, 3431, 3432, 3433, 3434, 3435, 3436, 3437, 3438, 3439, 3440, 3441, 3442, 3443, 3444, 3445, 3446, 3447, 3448, 3449, 3450, 3451, 3452, 3453, 3454, 3455, 3456, 3457, 3458, 3459, 3460, 3461, 3462, 3463, 3464, 3465, 3466, 3467, 3468, 3469, 3470, 3471, 3472, 3473, 3474, 3475, 3476, 3477, 3478, 3479, 3480, 3481, 3482, 3483, 3484, 3485, 3486, 3487, 3488, 3489, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498, 3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510, 3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521, 3522, 3523, 3524, 3525, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533, 3534, 3535, 3536, 3537, 3538, 3539, 3540, 3541, 3542, 3543, 3544, 3545, 3546, 3547, 3548, 3549, 3550, 3551, 3552, 3553, 3554, 3555, 3556, 3557, 3558, 3559, 3560, 3561, 3562, 3563, 3564, 3565, 3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3576, 3577, 3578, 3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 3619, 3620, 3621, 3622, 3623, 3624, 3625, 3626, 3627, 3628, 3629, 3630, 3631, 3632, 3633, 3634, 3635, 3636, 3637, 3638, 3639, 3640, 3641, 3642, 3643, 3644, 3645, 3646, 3647, 3648, 3649, 3650, 3651, 3652, 3653, 3654, 3655, 3656, 3657, 3658, 3659, 3660, 3661, 3662, 3663, 3664, 3665, 3666, 3667, 3668, 3669, 3670, 3671, 3672, 3673, 3674, 3675, 3676, 3677, 3678, 3679, 3680, 3681, 3682, 3683, 3684, 3685, 3686, 3687, 3688, 3689, 3690, 3691, 3692, 3693, 3694, 3695, 3696, 3697, 3698, 3699, 3700, 3701, 3702, 3703, 3704, 3705, 3706, 3707, 3708, 3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719, 3720);
DELETE FROM rooms WHERE id IN (3421, 3422, 3423, 3424, 3425, 3426, 3427, 3428, 3429, 3430, 3431, 3432, 3433, 3434, 3435, 3436, 3437, 3438, 3439, 3440, 3441, 3442, 3443, 3444, 3445, 3446, 3447, 3448, 3449, 3450, 3451, 3452, 3453, 3454, 3455, 3456, 3457, 3458, 3459, 3460, 3461, 3462, 3463, 3464, 3465, 3466, 3467, 3468, 3469, 3470, 3471, 3472, 3473, 3474, 3475, 3476, 3477, 3478, 3479, 3480, 3481, 3482, 3483, 3484, 3485, 3486, 3487, 3488, 3489, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498, 3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510, 3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521, 3522, 3523, 3524, 3525, 3526, 3527, 3528, 3529, 3530, 3531, 3532, 3533, 3534, 3535, 3536, 3537, 3538, 3539, 3540, 3541, 3542, 3543, 3544, 3545, 3546, 3547, 3548, 3549, 3550, 3551, 3552, 3553, 3554, 3555, 3556, 3557, 3558, 3559, 3560, 3561, 3562, 3563, 3564, 3565, 3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3576, 3577, 3578, 3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589, 3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 3619, 3620, 3621, 3622, 3623, 3624, 3625, 3626, 3627, 3628, 3629, 3630, 3631, 3632, 3633, 3634, 3635, 3636, 3637, 3638, 3639, 3640, 3641, 3642, 3643, 3644, 3645, 3646, 3647, 3648, 3649, 3650, 3651, 3652, 3653, 3654, 3655, 3656, 3657, 3658, 3659, 3660, 3661, 3662, 3663, 3664, 3665, 3666, 3667, 3668, 3669, 3670, 3671, 3672, 3673, 3674, 3675, 3676, 3677, 3678, 3679, 3680, 3681, 3682, 3683, 3684, 3685, 3686, 3687, 3688, 3689, 3690, 3691, 3692, 3693, 3694, 3695, 3696, 3697, 3698, 3699, 3700, 3701, 3702, 3703, 3704, 3705, 3706, 3707, 3708, 3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719, 3720);
DELETE FROM users WHERE id IN (830, 831, 832, 833, 834, 835, 836, 837, 838, 839, 840, 841, 842, 843, 844, 845, 846, 847, 848, 849, 850, 851, 852, 853, 854, 855);

COMMIT;
