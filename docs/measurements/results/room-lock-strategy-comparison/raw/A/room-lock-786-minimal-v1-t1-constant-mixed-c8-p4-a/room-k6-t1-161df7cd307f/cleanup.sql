\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-161df7cd307f'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (856, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-host@example.invalid'),
    (857, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-cancel-0@example.invalid'),
    (858, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-cancel-1@example.invalid'),
    (859, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-cancel-2@example.invalid'),
    (860, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-cancel-3@example.invalid'),
    (861, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-waiter-0@example.invalid'),
    (862, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-waiter-1@example.invalid'),
    (863, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-waiter-2@example.invalid'),
    (864, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-waiter-3@example.invalid'),
    (865, 'room-k6.room-k6-t1-161df7cd307f.t1-mixed-waiter-4@example.invalid'),
    (866, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s4-host@example.invalid'),
    (867, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s4-cancel@example.invalid'),
    (868, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s4-waiter-0@example.invalid'),
    (869, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s4-waiter-1@example.invalid'),
    (870, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s5-host@example.invalid'),
    (871, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s5-cancel@example.invalid'),
    (872, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s5-waiter-0@example.invalid'),
    (873, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s5-waiter-1@example.invalid'),
    (874, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s6-host@example.invalid'),
    (875, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s6-cancel@example.invalid'),
    (876, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s6-waiter-0@example.invalid'),
    (877, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s6-waiter-1@example.invalid'),
    (878, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s7-host@example.invalid'),
    (879, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s7-cancel@example.invalid'),
    (880, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s7-waiter-0@example.invalid'),
    (881, 'room-k6.room-k6-t1-161df7cd307f.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (3721, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r0-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3722, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r0-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3723, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r0-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3724, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r0-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3725, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r0-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3726, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r1-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3727, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r1-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3728, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r1-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3729, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r1-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3730, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r1-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3731, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r2-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3732, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r2-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3733, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r2-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3734, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r2-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3735, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r2-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3736, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r3-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3737, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r3-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3738, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r3-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3739, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r3-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3740, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r3-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3741, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r4-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3742, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r4-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3743, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r4-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3744, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r4-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3745, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r4-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3746, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r5-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3747, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r5-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3748, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r5-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3749, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r5-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3750, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r5-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3751, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r6-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3752, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r6-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3753, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r6-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3754, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r6-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3755, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r6-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3756, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r7-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3757, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r7-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3758, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r7-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3759, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r7-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3760, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r7-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3761, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r8-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3762, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r8-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3763, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r8-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3764, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r8-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3765, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r8-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3766, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r9-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3767, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r9-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3768, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r9-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3769, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r9-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3770, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r9-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3771, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r10-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3772, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r10-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3773, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r10-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3774, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r10-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3775, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r10-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3776, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r11-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3777, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r11-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3778, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r11-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3779, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r11-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3780, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r11-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3781, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r12-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3782, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r12-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3783, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r12-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3784, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r12-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3785, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r12-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3786, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r13-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3787, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r13-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3788, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r13-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3789, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r13-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3790, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r13-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3791, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r14-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3792, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r14-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3793, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r14-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3794, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r14-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3795, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r14-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3796, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r15-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3797, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r15-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3798, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r15-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3799, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r15-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3800, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r15-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3801, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r16-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3802, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r16-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3803, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r16-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3804, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r16-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3805, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r16-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3806, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r17-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3807, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r17-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3808, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r17-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3809, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r17-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3810, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r17-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3811, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r18-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3812, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r18-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3813, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r18-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3814, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r18-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3815, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r18-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3816, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r19-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3817, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r19-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3818, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r19-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3819, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r19-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3820, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r19-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3821, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r20-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3822, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r20-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3823, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r20-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3824, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r20-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3825, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r20-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3826, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r21-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3827, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r21-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3828, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r21-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3829, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r21-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3830, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r21-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3831, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r22-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3832, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r22-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3833, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r22-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3834, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r22-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3835, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r22-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3836, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r23-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3837, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r23-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3838, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r23-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3839, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r23-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3840, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r23-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3841, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r24-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3842, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r24-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3843, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r24-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3844, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r24-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3845, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r24-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3846, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r25-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3847, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r25-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3848, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r25-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3849, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r25-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3850, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r25-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3851, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r26-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3852, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r26-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3853, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r26-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3854, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r26-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3855, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r26-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3856, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r27-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3857, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r27-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3858, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r27-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3859, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r27-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3860, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r27-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3861, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r28-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3862, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r28-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3863, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r28-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3864, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r28-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3865, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r28-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3866, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r29-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3867, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r29-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3868, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r29-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3869, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r29-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3870, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r29-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3871, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r30-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3872, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r30-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3873, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r30-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3874, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r30-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3875, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r30-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3876, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r31-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3877, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r31-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3878, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r31-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3879, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r31-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3880, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r31-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3881, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r32-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3882, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r32-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3883, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r32-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3884, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r32-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3885, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r32-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3886, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r33-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3887, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r33-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3888, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r33-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3889, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r33-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3890, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r33-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3891, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r34-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3892, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r34-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3893, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r34-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3894, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r34-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3895, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r34-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3896, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r35-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3897, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r35-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3898, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r35-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3899, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r35-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3900, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r35-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3901, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r36-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3902, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r36-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3903, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r36-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3904, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r36-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3905, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r36-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3906, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r37-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3907, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r37-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3908, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r37-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3909, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r37-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3910, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r37-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3911, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r38-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3912, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r38-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3913, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r38-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3914, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r38-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3915, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r38-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3916, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r39-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3917, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r39-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3918, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r39-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3919, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r39-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3920, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r39-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3921, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r40-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3922, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r40-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3923, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r40-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3924, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r40-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3925, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r40-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3926, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r41-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3927, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r41-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3928, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r41-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3929, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r41-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3930, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r41-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3931, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r42-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3932, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r42-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3933, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r42-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3934, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r42-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3935, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r42-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3936, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r43-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3937, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r43-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3938, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r43-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3939, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r43-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3940, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r43-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3941, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r44-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3942, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r44-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3943, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r44-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3944, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r44-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3945, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r44-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3946, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r45-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3947, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r45-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3948, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r45-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3949, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r45-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3950, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r45-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3951, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r46-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3952, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r46-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3953, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r46-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3954, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r46-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3955, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r46-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3956, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r47-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3957, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r47-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3958, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r47-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3959, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r47-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3960, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r47-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3961, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r48-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3962, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r48-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3963, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r48-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3964, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r48-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3965, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r48-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3966, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r49-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3967, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r49-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3968, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r49-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3969, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r49-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3970, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r49-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3971, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r50-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3972, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r50-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3973, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r50-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3974, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r50-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3975, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r50-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3976, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r51-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3977, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r51-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3978, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r51-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3979, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r51-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3980, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r51-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3981, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r52-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3982, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r52-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3983, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r52-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3984, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r52-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3985, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r52-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3986, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r53-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3987, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r53-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3988, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r53-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3989, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r53-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3990, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r53-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3991, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r54-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3992, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r54-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3993, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r54-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3994, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r54-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3995, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r54-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3996, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r55-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3997, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r55-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3998, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r55-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (3999, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r55-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4000, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r55-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4001, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r56-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4002, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r56-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4003, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r56-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4004, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r56-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4005, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r56-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4006, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r57-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4007, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r57-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4008, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r57-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4009, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r57-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4010, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r57-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4011, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r58-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4012, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r58-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4013, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r58-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4014, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r58-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4015, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r58-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4016, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r59-mixed-hot', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4017, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r59-spread-s4', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4018, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r59-spread-s5', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4019, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r59-spread-s6', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471'),
    (4020, 'ROOM-K6 room-k6-t1-161df7cd307f t1-r59-spread-s7', 'ROOM k6 fixture 9e11ec1656c94b208afd5e366f1e1471');

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

DELETE FROM notifications WHERE room_id IN (3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 3729, 3730, 3731, 3732, 3733, 3734, 3735, 3736, 3737, 3738, 3739, 3740, 3741, 3742, 3743, 3744, 3745, 3746, 3747, 3748, 3749, 3750, 3751, 3752, 3753, 3754, 3755, 3756, 3757, 3758, 3759, 3760, 3761, 3762, 3763, 3764, 3765, 3766, 3767, 3768, 3769, 3770, 3771, 3772, 3773, 3774, 3775, 3776, 3777, 3778, 3779, 3780, 3781, 3782, 3783, 3784, 3785, 3786, 3787, 3788, 3789, 3790, 3791, 3792, 3793, 3794, 3795, 3796, 3797, 3798, 3799, 3800, 3801, 3802, 3803, 3804, 3805, 3806, 3807, 3808, 3809, 3810, 3811, 3812, 3813, 3814, 3815, 3816, 3817, 3818, 3819, 3820, 3821, 3822, 3823, 3824, 3825, 3826, 3827, 3828, 3829, 3830, 3831, 3832, 3833, 3834, 3835, 3836, 3837, 3838, 3839, 3840, 3841, 3842, 3843, 3844, 3845, 3846, 3847, 3848, 3849, 3850, 3851, 3852, 3853, 3854, 3855, 3856, 3857, 3858, 3859, 3860, 3861, 3862, 3863, 3864, 3865, 3866, 3867, 3868, 3869, 3870, 3871, 3872, 3873, 3874, 3875, 3876, 3877, 3878, 3879, 3880, 3881, 3882, 3883, 3884, 3885, 3886, 3887, 3888, 3889, 3890, 3891, 3892, 3893, 3894, 3895, 3896, 3897, 3898, 3899, 3900, 3901, 3902, 3903, 3904, 3905, 3906, 3907, 3908, 3909, 3910, 3911, 3912, 3913, 3914, 3915, 3916, 3917, 3918, 3919, 3920, 3921, 3922, 3923, 3924, 3925, 3926, 3927, 3928, 3929, 3930, 3931, 3932, 3933, 3934, 3935, 3936, 3937, 3938, 3939, 3940, 3941, 3942, 3943, 3944, 3945, 3946, 3947, 3948, 3949, 3950, 3951, 3952, 3953, 3954, 3955, 3956, 3957, 3958, 3959, 3960, 3961, 3962, 3963, 3964, 3965, 3966, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 3975, 3976, 3977, 3978, 3979, 3980, 3981, 3982, 3983, 3984, 3985, 3986, 3987, 3988, 3989, 3990, 3991, 3992, 3993, 3994, 3995, 3996, 3997, 3998, 3999, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009, 4010, 4011, 4012, 4013, 4014, 4015, 4016, 4017, 4018, 4019, 4020);
DELETE FROM notification_outbox_events WHERE room_id IN (3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 3729, 3730, 3731, 3732, 3733, 3734, 3735, 3736, 3737, 3738, 3739, 3740, 3741, 3742, 3743, 3744, 3745, 3746, 3747, 3748, 3749, 3750, 3751, 3752, 3753, 3754, 3755, 3756, 3757, 3758, 3759, 3760, 3761, 3762, 3763, 3764, 3765, 3766, 3767, 3768, 3769, 3770, 3771, 3772, 3773, 3774, 3775, 3776, 3777, 3778, 3779, 3780, 3781, 3782, 3783, 3784, 3785, 3786, 3787, 3788, 3789, 3790, 3791, 3792, 3793, 3794, 3795, 3796, 3797, 3798, 3799, 3800, 3801, 3802, 3803, 3804, 3805, 3806, 3807, 3808, 3809, 3810, 3811, 3812, 3813, 3814, 3815, 3816, 3817, 3818, 3819, 3820, 3821, 3822, 3823, 3824, 3825, 3826, 3827, 3828, 3829, 3830, 3831, 3832, 3833, 3834, 3835, 3836, 3837, 3838, 3839, 3840, 3841, 3842, 3843, 3844, 3845, 3846, 3847, 3848, 3849, 3850, 3851, 3852, 3853, 3854, 3855, 3856, 3857, 3858, 3859, 3860, 3861, 3862, 3863, 3864, 3865, 3866, 3867, 3868, 3869, 3870, 3871, 3872, 3873, 3874, 3875, 3876, 3877, 3878, 3879, 3880, 3881, 3882, 3883, 3884, 3885, 3886, 3887, 3888, 3889, 3890, 3891, 3892, 3893, 3894, 3895, 3896, 3897, 3898, 3899, 3900, 3901, 3902, 3903, 3904, 3905, 3906, 3907, 3908, 3909, 3910, 3911, 3912, 3913, 3914, 3915, 3916, 3917, 3918, 3919, 3920, 3921, 3922, 3923, 3924, 3925, 3926, 3927, 3928, 3929, 3930, 3931, 3932, 3933, 3934, 3935, 3936, 3937, 3938, 3939, 3940, 3941, 3942, 3943, 3944, 3945, 3946, 3947, 3948, 3949, 3950, 3951, 3952, 3953, 3954, 3955, 3956, 3957, 3958, 3959, 3960, 3961, 3962, 3963, 3964, 3965, 3966, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 3975, 3976, 3977, 3978, 3979, 3980, 3981, 3982, 3983, 3984, 3985, 3986, 3987, 3988, 3989, 3990, 3991, 3992, 3993, 3994, 3995, 3996, 3997, 3998, 3999, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009, 4010, 4011, 4012, 4013, 4014, 4015, 4016, 4017, 4018, 4019, 4020);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 3729, 3730, 3731, 3732, 3733, 3734, 3735, 3736, 3737, 3738, 3739, 3740, 3741, 3742, 3743, 3744, 3745, 3746, 3747, 3748, 3749, 3750, 3751, 3752, 3753, 3754, 3755, 3756, 3757, 3758, 3759, 3760, 3761, 3762, 3763, 3764, 3765, 3766, 3767, 3768, 3769, 3770, 3771, 3772, 3773, 3774, 3775, 3776, 3777, 3778, 3779, 3780, 3781, 3782, 3783, 3784, 3785, 3786, 3787, 3788, 3789, 3790, 3791, 3792, 3793, 3794, 3795, 3796, 3797, 3798, 3799, 3800, 3801, 3802, 3803, 3804, 3805, 3806, 3807, 3808, 3809, 3810, 3811, 3812, 3813, 3814, 3815, 3816, 3817, 3818, 3819, 3820, 3821, 3822, 3823, 3824, 3825, 3826, 3827, 3828, 3829, 3830, 3831, 3832, 3833, 3834, 3835, 3836, 3837, 3838, 3839, 3840, 3841, 3842, 3843, 3844, 3845, 3846, 3847, 3848, 3849, 3850, 3851, 3852, 3853, 3854, 3855, 3856, 3857, 3858, 3859, 3860, 3861, 3862, 3863, 3864, 3865, 3866, 3867, 3868, 3869, 3870, 3871, 3872, 3873, 3874, 3875, 3876, 3877, 3878, 3879, 3880, 3881, 3882, 3883, 3884, 3885, 3886, 3887, 3888, 3889, 3890, 3891, 3892, 3893, 3894, 3895, 3896, 3897, 3898, 3899, 3900, 3901, 3902, 3903, 3904, 3905, 3906, 3907, 3908, 3909, 3910, 3911, 3912, 3913, 3914, 3915, 3916, 3917, 3918, 3919, 3920, 3921, 3922, 3923, 3924, 3925, 3926, 3927, 3928, 3929, 3930, 3931, 3932, 3933, 3934, 3935, 3936, 3937, 3938, 3939, 3940, 3941, 3942, 3943, 3944, 3945, 3946, 3947, 3948, 3949, 3950, 3951, 3952, 3953, 3954, 3955, 3956, 3957, 3958, 3959, 3960, 3961, 3962, 3963, 3964, 3965, 3966, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 3975, 3976, 3977, 3978, 3979, 3980, 3981, 3982, 3983, 3984, 3985, 3986, 3987, 3988, 3989, 3990, 3991, 3992, 3993, 3994, 3995, 3996, 3997, 3998, 3999, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009, 4010, 4011, 4012, 4013, 4014, 4015, 4016, 4017, 4018, 4019, 4020)
);
DELETE FROM chat_rooms WHERE room_id IN (3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 3729, 3730, 3731, 3732, 3733, 3734, 3735, 3736, 3737, 3738, 3739, 3740, 3741, 3742, 3743, 3744, 3745, 3746, 3747, 3748, 3749, 3750, 3751, 3752, 3753, 3754, 3755, 3756, 3757, 3758, 3759, 3760, 3761, 3762, 3763, 3764, 3765, 3766, 3767, 3768, 3769, 3770, 3771, 3772, 3773, 3774, 3775, 3776, 3777, 3778, 3779, 3780, 3781, 3782, 3783, 3784, 3785, 3786, 3787, 3788, 3789, 3790, 3791, 3792, 3793, 3794, 3795, 3796, 3797, 3798, 3799, 3800, 3801, 3802, 3803, 3804, 3805, 3806, 3807, 3808, 3809, 3810, 3811, 3812, 3813, 3814, 3815, 3816, 3817, 3818, 3819, 3820, 3821, 3822, 3823, 3824, 3825, 3826, 3827, 3828, 3829, 3830, 3831, 3832, 3833, 3834, 3835, 3836, 3837, 3838, 3839, 3840, 3841, 3842, 3843, 3844, 3845, 3846, 3847, 3848, 3849, 3850, 3851, 3852, 3853, 3854, 3855, 3856, 3857, 3858, 3859, 3860, 3861, 3862, 3863, 3864, 3865, 3866, 3867, 3868, 3869, 3870, 3871, 3872, 3873, 3874, 3875, 3876, 3877, 3878, 3879, 3880, 3881, 3882, 3883, 3884, 3885, 3886, 3887, 3888, 3889, 3890, 3891, 3892, 3893, 3894, 3895, 3896, 3897, 3898, 3899, 3900, 3901, 3902, 3903, 3904, 3905, 3906, 3907, 3908, 3909, 3910, 3911, 3912, 3913, 3914, 3915, 3916, 3917, 3918, 3919, 3920, 3921, 3922, 3923, 3924, 3925, 3926, 3927, 3928, 3929, 3930, 3931, 3932, 3933, 3934, 3935, 3936, 3937, 3938, 3939, 3940, 3941, 3942, 3943, 3944, 3945, 3946, 3947, 3948, 3949, 3950, 3951, 3952, 3953, 3954, 3955, 3956, 3957, 3958, 3959, 3960, 3961, 3962, 3963, 3964, 3965, 3966, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 3975, 3976, 3977, 3978, 3979, 3980, 3981, 3982, 3983, 3984, 3985, 3986, 3987, 3988, 3989, 3990, 3991, 3992, 3993, 3994, 3995, 3996, 3997, 3998, 3999, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009, 4010, 4011, 4012, 4013, 4014, 4015, 4016, 4017, 4018, 4019, 4020);
DELETE FROM room_waitlists WHERE room_id IN (3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 3729, 3730, 3731, 3732, 3733, 3734, 3735, 3736, 3737, 3738, 3739, 3740, 3741, 3742, 3743, 3744, 3745, 3746, 3747, 3748, 3749, 3750, 3751, 3752, 3753, 3754, 3755, 3756, 3757, 3758, 3759, 3760, 3761, 3762, 3763, 3764, 3765, 3766, 3767, 3768, 3769, 3770, 3771, 3772, 3773, 3774, 3775, 3776, 3777, 3778, 3779, 3780, 3781, 3782, 3783, 3784, 3785, 3786, 3787, 3788, 3789, 3790, 3791, 3792, 3793, 3794, 3795, 3796, 3797, 3798, 3799, 3800, 3801, 3802, 3803, 3804, 3805, 3806, 3807, 3808, 3809, 3810, 3811, 3812, 3813, 3814, 3815, 3816, 3817, 3818, 3819, 3820, 3821, 3822, 3823, 3824, 3825, 3826, 3827, 3828, 3829, 3830, 3831, 3832, 3833, 3834, 3835, 3836, 3837, 3838, 3839, 3840, 3841, 3842, 3843, 3844, 3845, 3846, 3847, 3848, 3849, 3850, 3851, 3852, 3853, 3854, 3855, 3856, 3857, 3858, 3859, 3860, 3861, 3862, 3863, 3864, 3865, 3866, 3867, 3868, 3869, 3870, 3871, 3872, 3873, 3874, 3875, 3876, 3877, 3878, 3879, 3880, 3881, 3882, 3883, 3884, 3885, 3886, 3887, 3888, 3889, 3890, 3891, 3892, 3893, 3894, 3895, 3896, 3897, 3898, 3899, 3900, 3901, 3902, 3903, 3904, 3905, 3906, 3907, 3908, 3909, 3910, 3911, 3912, 3913, 3914, 3915, 3916, 3917, 3918, 3919, 3920, 3921, 3922, 3923, 3924, 3925, 3926, 3927, 3928, 3929, 3930, 3931, 3932, 3933, 3934, 3935, 3936, 3937, 3938, 3939, 3940, 3941, 3942, 3943, 3944, 3945, 3946, 3947, 3948, 3949, 3950, 3951, 3952, 3953, 3954, 3955, 3956, 3957, 3958, 3959, 3960, 3961, 3962, 3963, 3964, 3965, 3966, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 3975, 3976, 3977, 3978, 3979, 3980, 3981, 3982, 3983, 3984, 3985, 3986, 3987, 3988, 3989, 3990, 3991, 3992, 3993, 3994, 3995, 3996, 3997, 3998, 3999, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009, 4010, 4011, 4012, 4013, 4014, 4015, 4016, 4017, 4018, 4019, 4020);
DELETE FROM participations WHERE room_id IN (3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 3729, 3730, 3731, 3732, 3733, 3734, 3735, 3736, 3737, 3738, 3739, 3740, 3741, 3742, 3743, 3744, 3745, 3746, 3747, 3748, 3749, 3750, 3751, 3752, 3753, 3754, 3755, 3756, 3757, 3758, 3759, 3760, 3761, 3762, 3763, 3764, 3765, 3766, 3767, 3768, 3769, 3770, 3771, 3772, 3773, 3774, 3775, 3776, 3777, 3778, 3779, 3780, 3781, 3782, 3783, 3784, 3785, 3786, 3787, 3788, 3789, 3790, 3791, 3792, 3793, 3794, 3795, 3796, 3797, 3798, 3799, 3800, 3801, 3802, 3803, 3804, 3805, 3806, 3807, 3808, 3809, 3810, 3811, 3812, 3813, 3814, 3815, 3816, 3817, 3818, 3819, 3820, 3821, 3822, 3823, 3824, 3825, 3826, 3827, 3828, 3829, 3830, 3831, 3832, 3833, 3834, 3835, 3836, 3837, 3838, 3839, 3840, 3841, 3842, 3843, 3844, 3845, 3846, 3847, 3848, 3849, 3850, 3851, 3852, 3853, 3854, 3855, 3856, 3857, 3858, 3859, 3860, 3861, 3862, 3863, 3864, 3865, 3866, 3867, 3868, 3869, 3870, 3871, 3872, 3873, 3874, 3875, 3876, 3877, 3878, 3879, 3880, 3881, 3882, 3883, 3884, 3885, 3886, 3887, 3888, 3889, 3890, 3891, 3892, 3893, 3894, 3895, 3896, 3897, 3898, 3899, 3900, 3901, 3902, 3903, 3904, 3905, 3906, 3907, 3908, 3909, 3910, 3911, 3912, 3913, 3914, 3915, 3916, 3917, 3918, 3919, 3920, 3921, 3922, 3923, 3924, 3925, 3926, 3927, 3928, 3929, 3930, 3931, 3932, 3933, 3934, 3935, 3936, 3937, 3938, 3939, 3940, 3941, 3942, 3943, 3944, 3945, 3946, 3947, 3948, 3949, 3950, 3951, 3952, 3953, 3954, 3955, 3956, 3957, 3958, 3959, 3960, 3961, 3962, 3963, 3964, 3965, 3966, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 3975, 3976, 3977, 3978, 3979, 3980, 3981, 3982, 3983, 3984, 3985, 3986, 3987, 3988, 3989, 3990, 3991, 3992, 3993, 3994, 3995, 3996, 3997, 3998, 3999, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009, 4010, 4011, 4012, 4013, 4014, 4015, 4016, 4017, 4018, 4019, 4020);
DELETE FROM rooms WHERE id IN (3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 3729, 3730, 3731, 3732, 3733, 3734, 3735, 3736, 3737, 3738, 3739, 3740, 3741, 3742, 3743, 3744, 3745, 3746, 3747, 3748, 3749, 3750, 3751, 3752, 3753, 3754, 3755, 3756, 3757, 3758, 3759, 3760, 3761, 3762, 3763, 3764, 3765, 3766, 3767, 3768, 3769, 3770, 3771, 3772, 3773, 3774, 3775, 3776, 3777, 3778, 3779, 3780, 3781, 3782, 3783, 3784, 3785, 3786, 3787, 3788, 3789, 3790, 3791, 3792, 3793, 3794, 3795, 3796, 3797, 3798, 3799, 3800, 3801, 3802, 3803, 3804, 3805, 3806, 3807, 3808, 3809, 3810, 3811, 3812, 3813, 3814, 3815, 3816, 3817, 3818, 3819, 3820, 3821, 3822, 3823, 3824, 3825, 3826, 3827, 3828, 3829, 3830, 3831, 3832, 3833, 3834, 3835, 3836, 3837, 3838, 3839, 3840, 3841, 3842, 3843, 3844, 3845, 3846, 3847, 3848, 3849, 3850, 3851, 3852, 3853, 3854, 3855, 3856, 3857, 3858, 3859, 3860, 3861, 3862, 3863, 3864, 3865, 3866, 3867, 3868, 3869, 3870, 3871, 3872, 3873, 3874, 3875, 3876, 3877, 3878, 3879, 3880, 3881, 3882, 3883, 3884, 3885, 3886, 3887, 3888, 3889, 3890, 3891, 3892, 3893, 3894, 3895, 3896, 3897, 3898, 3899, 3900, 3901, 3902, 3903, 3904, 3905, 3906, 3907, 3908, 3909, 3910, 3911, 3912, 3913, 3914, 3915, 3916, 3917, 3918, 3919, 3920, 3921, 3922, 3923, 3924, 3925, 3926, 3927, 3928, 3929, 3930, 3931, 3932, 3933, 3934, 3935, 3936, 3937, 3938, 3939, 3940, 3941, 3942, 3943, 3944, 3945, 3946, 3947, 3948, 3949, 3950, 3951, 3952, 3953, 3954, 3955, 3956, 3957, 3958, 3959, 3960, 3961, 3962, 3963, 3964, 3965, 3966, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 3975, 3976, 3977, 3978, 3979, 3980, 3981, 3982, 3983, 3984, 3985, 3986, 3987, 3988, 3989, 3990, 3991, 3992, 3993, 3994, 3995, 3996, 3997, 3998, 3999, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009, 4010, 4011, 4012, 4013, 4014, 4015, 4016, 4017, 4018, 4019, 4020);
DELETE FROM users WHERE id IN (856, 857, 858, 859, 860, 861, 862, 863, 864, 865, 866, 867, 868, 869, 870, 871, 872, 873, 874, 875, 876, 877, 878, 879, 880, 881);

COMMIT;
