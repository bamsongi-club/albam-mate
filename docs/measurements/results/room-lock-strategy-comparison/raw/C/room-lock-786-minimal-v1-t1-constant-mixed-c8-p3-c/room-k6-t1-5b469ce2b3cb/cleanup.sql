\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('room-k6-t1-5b469ce2b3cb'));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    (778, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-host@example.invalid'),
    (779, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-cancel-0@example.invalid'),
    (780, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-cancel-1@example.invalid'),
    (781, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-cancel-2@example.invalid'),
    (782, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-cancel-3@example.invalid'),
    (783, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-waiter-0@example.invalid'),
    (784, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-waiter-1@example.invalid'),
    (785, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-waiter-2@example.invalid'),
    (786, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-waiter-3@example.invalid'),
    (787, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-mixed-waiter-4@example.invalid'),
    (788, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s4-host@example.invalid'),
    (789, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s4-cancel@example.invalid'),
    (790, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s4-waiter-0@example.invalid'),
    (791, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s4-waiter-1@example.invalid'),
    (792, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s5-host@example.invalid'),
    (793, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s5-cancel@example.invalid'),
    (794, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s5-waiter-0@example.invalid'),
    (795, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s5-waiter-1@example.invalid'),
    (796, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s6-host@example.invalid'),
    (797, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s6-cancel@example.invalid'),
    (798, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s6-waiter-0@example.invalid'),
    (799, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s6-waiter-1@example.invalid'),
    (800, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s7-host@example.invalid'),
    (801, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s7-cancel@example.invalid'),
    (802, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s7-waiter-0@example.invalid'),
    (803, 'room-k6.room-k6-t1-5b469ce2b3cb.t1-spread-s7-waiter-1@example.invalid');

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    (2821, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r0-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2822, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r0-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2823, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r0-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2824, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r0-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2825, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r0-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2826, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r1-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2827, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r1-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2828, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r1-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2829, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r1-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2830, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r1-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2831, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r2-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2832, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r2-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2833, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r2-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2834, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r2-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2835, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r2-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2836, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r3-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2837, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r3-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2838, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r3-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2839, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r3-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2840, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r3-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2841, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r4-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2842, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r4-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2843, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r4-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2844, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r4-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2845, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r4-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2846, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r5-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2847, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r5-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2848, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r5-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2849, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r5-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2850, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r5-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2851, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r6-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2852, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r6-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2853, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r6-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2854, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r6-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2855, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r6-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2856, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r7-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2857, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r7-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2858, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r7-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2859, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r7-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2860, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r7-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2861, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r8-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2862, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r8-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2863, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r8-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2864, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r8-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2865, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r8-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2866, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r9-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2867, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r9-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2868, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r9-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2869, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r9-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2870, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r9-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2871, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r10-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2872, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r10-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2873, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r10-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2874, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r10-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2875, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r10-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2876, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r11-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2877, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r11-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2878, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r11-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2879, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r11-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2880, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r11-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2881, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r12-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2882, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r12-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2883, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r12-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2884, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r12-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2885, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r12-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2886, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r13-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2887, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r13-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2888, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r13-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2889, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r13-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2890, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r13-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2891, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r14-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2892, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r14-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2893, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r14-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2894, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r14-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2895, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r14-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2896, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r15-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2897, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r15-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2898, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r15-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2899, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r15-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2900, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r15-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2901, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r16-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2902, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r16-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2903, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r16-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2904, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r16-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2905, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r16-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2906, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r17-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2907, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r17-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2908, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r17-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2909, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r17-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2910, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r17-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2911, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r18-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2912, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r18-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2913, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r18-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2914, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r18-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2915, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r18-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2916, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r19-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2917, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r19-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2918, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r19-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2919, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r19-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2920, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r19-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2921, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r20-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2922, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r20-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2923, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r20-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2924, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r20-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2925, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r20-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2926, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r21-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2927, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r21-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2928, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r21-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2929, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r21-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2930, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r21-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2931, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r22-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2932, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r22-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2933, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r22-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2934, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r22-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2935, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r22-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2936, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r23-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2937, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r23-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2938, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r23-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2939, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r23-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2940, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r23-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2941, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r24-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2942, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r24-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2943, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r24-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2944, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r24-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2945, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r24-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2946, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r25-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2947, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r25-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2948, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r25-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2949, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r25-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2950, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r25-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2951, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r26-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2952, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r26-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2953, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r26-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2954, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r26-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2955, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r26-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2956, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r27-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2957, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r27-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2958, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r27-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2959, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r27-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2960, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r27-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2961, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r28-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2962, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r28-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2963, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r28-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2964, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r28-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2965, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r28-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2966, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r29-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2967, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r29-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2968, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r29-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2969, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r29-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2970, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r29-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2971, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r30-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2972, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r30-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2973, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r30-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2974, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r30-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2975, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r30-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2976, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r31-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2977, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r31-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2978, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r31-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2979, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r31-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2980, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r31-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2981, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r32-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2982, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r32-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2983, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r32-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2984, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r32-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2985, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r32-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2986, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r33-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2987, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r33-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2988, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r33-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2989, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r33-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2990, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r33-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2991, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r34-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2992, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r34-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2993, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r34-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2994, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r34-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2995, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r34-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2996, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r35-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2997, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r35-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2998, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r35-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (2999, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r35-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3000, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r35-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3001, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r36-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3002, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r36-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3003, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r36-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3004, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r36-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3005, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r36-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3006, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r37-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3007, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r37-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3008, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r37-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3009, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r37-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3010, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r37-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3011, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r38-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3012, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r38-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3013, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r38-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3014, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r38-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3015, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r38-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3016, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r39-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3017, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r39-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3018, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r39-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3019, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r39-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3020, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r39-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3021, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r40-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3022, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r40-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3023, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r40-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3024, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r40-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3025, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r40-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3026, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r41-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3027, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r41-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3028, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r41-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3029, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r41-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3030, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r41-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3031, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r42-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3032, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r42-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3033, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r42-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3034, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r42-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3035, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r42-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3036, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r43-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3037, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r43-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3038, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r43-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3039, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r43-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3040, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r43-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3041, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r44-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3042, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r44-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3043, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r44-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3044, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r44-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3045, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r44-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3046, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r45-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3047, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r45-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3048, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r45-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3049, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r45-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3050, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r45-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3051, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r46-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3052, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r46-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3053, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r46-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3054, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r46-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3055, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r46-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3056, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r47-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3057, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r47-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3058, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r47-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3059, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r47-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3060, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r47-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3061, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r48-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3062, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r48-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3063, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r48-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3064, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r48-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3065, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r48-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3066, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r49-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3067, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r49-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3068, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r49-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3069, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r49-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3070, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r49-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3071, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r50-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3072, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r50-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3073, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r50-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3074, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r50-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3075, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r50-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3076, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r51-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3077, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r51-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3078, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r51-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3079, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r51-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3080, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r51-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3081, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r52-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3082, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r52-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3083, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r52-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3084, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r52-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3085, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r52-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3086, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r53-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3087, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r53-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3088, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r53-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3089, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r53-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3090, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r53-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3091, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r54-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3092, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r54-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3093, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r54-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3094, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r54-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3095, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r54-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3096, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r55-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3097, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r55-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3098, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r55-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3099, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r55-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3100, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r55-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3101, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r56-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3102, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r56-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3103, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r56-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3104, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r56-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3105, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r56-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3106, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r57-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3107, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r57-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3108, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r57-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3109, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r57-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3110, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r57-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3111, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r58-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3112, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r58-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3113, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r58-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3114, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r58-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3115, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r58-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3116, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r59-mixed-hot', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3117, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r59-spread-s4', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3118, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r59-spread-s5', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3119, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r59-spread-s6', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3'),
    (3120, 'ROOM-K6 room-k6-t1-5b469ce2b3cb t1-r59-spread-s7', 'ROOM k6 fixture aed796613f514cef8133ba737872f6e3');

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

DELETE FROM notifications WHERE room_id IN (2821, 2822, 2823, 2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 2836, 2837, 2838, 2839, 2840, 2841, 2842, 2843, 2844, 2845, 2846, 2847, 2848, 2849, 2850, 2851, 2852, 2853, 2854, 2855, 2856, 2857, 2858, 2859, 2860, 2861, 2862, 2863, 2864, 2865, 2866, 2867, 2868, 2869, 2870, 2871, 2872, 2873, 2874, 2875, 2876, 2877, 2878, 2879, 2880, 2881, 2882, 2883, 2884, 2885, 2886, 2887, 2888, 2889, 2890, 2891, 2892, 2893, 2894, 2895, 2896, 2897, 2898, 2899, 2900, 2901, 2902, 2903, 2904, 2905, 2906, 2907, 2908, 2909, 2910, 2911, 2912, 2913, 2914, 2915, 2916, 2917, 2918, 2919, 2920, 2921, 2922, 2923, 2924, 2925, 2926, 2927, 2928, 2929, 2930, 2931, 2932, 2933, 2934, 2935, 2936, 2937, 2938, 2939, 2940, 2941, 2942, 2943, 2944, 2945, 2946, 2947, 2948, 2949, 2950, 2951, 2952, 2953, 2954, 2955, 2956, 2957, 2958, 2959, 2960, 2961, 2962, 2963, 2964, 2965, 2966, 2967, 2968, 2969, 2970, 2971, 2972, 2973, 2974, 2975, 2976, 2977, 2978, 2979, 2980, 2981, 2982, 2983, 2984, 2985, 2986, 2987, 2988, 2989, 2990, 2991, 2992, 2993, 2994, 2995, 2996, 2997, 2998, 2999, 3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 3057, 3058, 3059, 3060, 3061, 3062, 3063, 3064, 3065, 3066, 3067, 3068, 3069, 3070, 3071, 3072, 3073, 3074, 3075, 3076, 3077, 3078, 3079, 3080, 3081, 3082, 3083, 3084, 3085, 3086, 3087, 3088, 3089, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3100, 3101, 3102, 3103, 3104, 3105, 3106, 3107, 3108, 3109, 3110, 3111, 3112, 3113, 3114, 3115, 3116, 3117, 3118, 3119, 3120);
DELETE FROM notification_outbox_events WHERE room_id IN (2821, 2822, 2823, 2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 2836, 2837, 2838, 2839, 2840, 2841, 2842, 2843, 2844, 2845, 2846, 2847, 2848, 2849, 2850, 2851, 2852, 2853, 2854, 2855, 2856, 2857, 2858, 2859, 2860, 2861, 2862, 2863, 2864, 2865, 2866, 2867, 2868, 2869, 2870, 2871, 2872, 2873, 2874, 2875, 2876, 2877, 2878, 2879, 2880, 2881, 2882, 2883, 2884, 2885, 2886, 2887, 2888, 2889, 2890, 2891, 2892, 2893, 2894, 2895, 2896, 2897, 2898, 2899, 2900, 2901, 2902, 2903, 2904, 2905, 2906, 2907, 2908, 2909, 2910, 2911, 2912, 2913, 2914, 2915, 2916, 2917, 2918, 2919, 2920, 2921, 2922, 2923, 2924, 2925, 2926, 2927, 2928, 2929, 2930, 2931, 2932, 2933, 2934, 2935, 2936, 2937, 2938, 2939, 2940, 2941, 2942, 2943, 2944, 2945, 2946, 2947, 2948, 2949, 2950, 2951, 2952, 2953, 2954, 2955, 2956, 2957, 2958, 2959, 2960, 2961, 2962, 2963, 2964, 2965, 2966, 2967, 2968, 2969, 2970, 2971, 2972, 2973, 2974, 2975, 2976, 2977, 2978, 2979, 2980, 2981, 2982, 2983, 2984, 2985, 2986, 2987, 2988, 2989, 2990, 2991, 2992, 2993, 2994, 2995, 2996, 2997, 2998, 2999, 3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 3057, 3058, 3059, 3060, 3061, 3062, 3063, 3064, 3065, 3066, 3067, 3068, 3069, 3070, 3071, 3072, 3073, 3074, 3075, 3076, 3077, 3078, 3079, 3080, 3081, 3082, 3083, 3084, 3085, 3086, 3087, 3088, 3089, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3100, 3101, 3102, 3103, 3104, 3105, 3106, 3107, 3108, 3109, 3110, 3111, 3112, 3113, 3114, 3115, 3116, 3117, 3118, 3119, 3120);
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (2821, 2822, 2823, 2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 2836, 2837, 2838, 2839, 2840, 2841, 2842, 2843, 2844, 2845, 2846, 2847, 2848, 2849, 2850, 2851, 2852, 2853, 2854, 2855, 2856, 2857, 2858, 2859, 2860, 2861, 2862, 2863, 2864, 2865, 2866, 2867, 2868, 2869, 2870, 2871, 2872, 2873, 2874, 2875, 2876, 2877, 2878, 2879, 2880, 2881, 2882, 2883, 2884, 2885, 2886, 2887, 2888, 2889, 2890, 2891, 2892, 2893, 2894, 2895, 2896, 2897, 2898, 2899, 2900, 2901, 2902, 2903, 2904, 2905, 2906, 2907, 2908, 2909, 2910, 2911, 2912, 2913, 2914, 2915, 2916, 2917, 2918, 2919, 2920, 2921, 2922, 2923, 2924, 2925, 2926, 2927, 2928, 2929, 2930, 2931, 2932, 2933, 2934, 2935, 2936, 2937, 2938, 2939, 2940, 2941, 2942, 2943, 2944, 2945, 2946, 2947, 2948, 2949, 2950, 2951, 2952, 2953, 2954, 2955, 2956, 2957, 2958, 2959, 2960, 2961, 2962, 2963, 2964, 2965, 2966, 2967, 2968, 2969, 2970, 2971, 2972, 2973, 2974, 2975, 2976, 2977, 2978, 2979, 2980, 2981, 2982, 2983, 2984, 2985, 2986, 2987, 2988, 2989, 2990, 2991, 2992, 2993, 2994, 2995, 2996, 2997, 2998, 2999, 3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 3057, 3058, 3059, 3060, 3061, 3062, 3063, 3064, 3065, 3066, 3067, 3068, 3069, 3070, 3071, 3072, 3073, 3074, 3075, 3076, 3077, 3078, 3079, 3080, 3081, 3082, 3083, 3084, 3085, 3086, 3087, 3088, 3089, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3100, 3101, 3102, 3103, 3104, 3105, 3106, 3107, 3108, 3109, 3110, 3111, 3112, 3113, 3114, 3115, 3116, 3117, 3118, 3119, 3120)
);
DELETE FROM chat_rooms WHERE room_id IN (2821, 2822, 2823, 2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 2836, 2837, 2838, 2839, 2840, 2841, 2842, 2843, 2844, 2845, 2846, 2847, 2848, 2849, 2850, 2851, 2852, 2853, 2854, 2855, 2856, 2857, 2858, 2859, 2860, 2861, 2862, 2863, 2864, 2865, 2866, 2867, 2868, 2869, 2870, 2871, 2872, 2873, 2874, 2875, 2876, 2877, 2878, 2879, 2880, 2881, 2882, 2883, 2884, 2885, 2886, 2887, 2888, 2889, 2890, 2891, 2892, 2893, 2894, 2895, 2896, 2897, 2898, 2899, 2900, 2901, 2902, 2903, 2904, 2905, 2906, 2907, 2908, 2909, 2910, 2911, 2912, 2913, 2914, 2915, 2916, 2917, 2918, 2919, 2920, 2921, 2922, 2923, 2924, 2925, 2926, 2927, 2928, 2929, 2930, 2931, 2932, 2933, 2934, 2935, 2936, 2937, 2938, 2939, 2940, 2941, 2942, 2943, 2944, 2945, 2946, 2947, 2948, 2949, 2950, 2951, 2952, 2953, 2954, 2955, 2956, 2957, 2958, 2959, 2960, 2961, 2962, 2963, 2964, 2965, 2966, 2967, 2968, 2969, 2970, 2971, 2972, 2973, 2974, 2975, 2976, 2977, 2978, 2979, 2980, 2981, 2982, 2983, 2984, 2985, 2986, 2987, 2988, 2989, 2990, 2991, 2992, 2993, 2994, 2995, 2996, 2997, 2998, 2999, 3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 3057, 3058, 3059, 3060, 3061, 3062, 3063, 3064, 3065, 3066, 3067, 3068, 3069, 3070, 3071, 3072, 3073, 3074, 3075, 3076, 3077, 3078, 3079, 3080, 3081, 3082, 3083, 3084, 3085, 3086, 3087, 3088, 3089, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3100, 3101, 3102, 3103, 3104, 3105, 3106, 3107, 3108, 3109, 3110, 3111, 3112, 3113, 3114, 3115, 3116, 3117, 3118, 3119, 3120);
DELETE FROM room_waitlists WHERE room_id IN (2821, 2822, 2823, 2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 2836, 2837, 2838, 2839, 2840, 2841, 2842, 2843, 2844, 2845, 2846, 2847, 2848, 2849, 2850, 2851, 2852, 2853, 2854, 2855, 2856, 2857, 2858, 2859, 2860, 2861, 2862, 2863, 2864, 2865, 2866, 2867, 2868, 2869, 2870, 2871, 2872, 2873, 2874, 2875, 2876, 2877, 2878, 2879, 2880, 2881, 2882, 2883, 2884, 2885, 2886, 2887, 2888, 2889, 2890, 2891, 2892, 2893, 2894, 2895, 2896, 2897, 2898, 2899, 2900, 2901, 2902, 2903, 2904, 2905, 2906, 2907, 2908, 2909, 2910, 2911, 2912, 2913, 2914, 2915, 2916, 2917, 2918, 2919, 2920, 2921, 2922, 2923, 2924, 2925, 2926, 2927, 2928, 2929, 2930, 2931, 2932, 2933, 2934, 2935, 2936, 2937, 2938, 2939, 2940, 2941, 2942, 2943, 2944, 2945, 2946, 2947, 2948, 2949, 2950, 2951, 2952, 2953, 2954, 2955, 2956, 2957, 2958, 2959, 2960, 2961, 2962, 2963, 2964, 2965, 2966, 2967, 2968, 2969, 2970, 2971, 2972, 2973, 2974, 2975, 2976, 2977, 2978, 2979, 2980, 2981, 2982, 2983, 2984, 2985, 2986, 2987, 2988, 2989, 2990, 2991, 2992, 2993, 2994, 2995, 2996, 2997, 2998, 2999, 3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 3057, 3058, 3059, 3060, 3061, 3062, 3063, 3064, 3065, 3066, 3067, 3068, 3069, 3070, 3071, 3072, 3073, 3074, 3075, 3076, 3077, 3078, 3079, 3080, 3081, 3082, 3083, 3084, 3085, 3086, 3087, 3088, 3089, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3100, 3101, 3102, 3103, 3104, 3105, 3106, 3107, 3108, 3109, 3110, 3111, 3112, 3113, 3114, 3115, 3116, 3117, 3118, 3119, 3120);
DELETE FROM participations WHERE room_id IN (2821, 2822, 2823, 2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 2836, 2837, 2838, 2839, 2840, 2841, 2842, 2843, 2844, 2845, 2846, 2847, 2848, 2849, 2850, 2851, 2852, 2853, 2854, 2855, 2856, 2857, 2858, 2859, 2860, 2861, 2862, 2863, 2864, 2865, 2866, 2867, 2868, 2869, 2870, 2871, 2872, 2873, 2874, 2875, 2876, 2877, 2878, 2879, 2880, 2881, 2882, 2883, 2884, 2885, 2886, 2887, 2888, 2889, 2890, 2891, 2892, 2893, 2894, 2895, 2896, 2897, 2898, 2899, 2900, 2901, 2902, 2903, 2904, 2905, 2906, 2907, 2908, 2909, 2910, 2911, 2912, 2913, 2914, 2915, 2916, 2917, 2918, 2919, 2920, 2921, 2922, 2923, 2924, 2925, 2926, 2927, 2928, 2929, 2930, 2931, 2932, 2933, 2934, 2935, 2936, 2937, 2938, 2939, 2940, 2941, 2942, 2943, 2944, 2945, 2946, 2947, 2948, 2949, 2950, 2951, 2952, 2953, 2954, 2955, 2956, 2957, 2958, 2959, 2960, 2961, 2962, 2963, 2964, 2965, 2966, 2967, 2968, 2969, 2970, 2971, 2972, 2973, 2974, 2975, 2976, 2977, 2978, 2979, 2980, 2981, 2982, 2983, 2984, 2985, 2986, 2987, 2988, 2989, 2990, 2991, 2992, 2993, 2994, 2995, 2996, 2997, 2998, 2999, 3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 3057, 3058, 3059, 3060, 3061, 3062, 3063, 3064, 3065, 3066, 3067, 3068, 3069, 3070, 3071, 3072, 3073, 3074, 3075, 3076, 3077, 3078, 3079, 3080, 3081, 3082, 3083, 3084, 3085, 3086, 3087, 3088, 3089, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3100, 3101, 3102, 3103, 3104, 3105, 3106, 3107, 3108, 3109, 3110, 3111, 3112, 3113, 3114, 3115, 3116, 3117, 3118, 3119, 3120);
DELETE FROM rooms WHERE id IN (2821, 2822, 2823, 2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 2836, 2837, 2838, 2839, 2840, 2841, 2842, 2843, 2844, 2845, 2846, 2847, 2848, 2849, 2850, 2851, 2852, 2853, 2854, 2855, 2856, 2857, 2858, 2859, 2860, 2861, 2862, 2863, 2864, 2865, 2866, 2867, 2868, 2869, 2870, 2871, 2872, 2873, 2874, 2875, 2876, 2877, 2878, 2879, 2880, 2881, 2882, 2883, 2884, 2885, 2886, 2887, 2888, 2889, 2890, 2891, 2892, 2893, 2894, 2895, 2896, 2897, 2898, 2899, 2900, 2901, 2902, 2903, 2904, 2905, 2906, 2907, 2908, 2909, 2910, 2911, 2912, 2913, 2914, 2915, 2916, 2917, 2918, 2919, 2920, 2921, 2922, 2923, 2924, 2925, 2926, 2927, 2928, 2929, 2930, 2931, 2932, 2933, 2934, 2935, 2936, 2937, 2938, 2939, 2940, 2941, 2942, 2943, 2944, 2945, 2946, 2947, 2948, 2949, 2950, 2951, 2952, 2953, 2954, 2955, 2956, 2957, 2958, 2959, 2960, 2961, 2962, 2963, 2964, 2965, 2966, 2967, 2968, 2969, 2970, 2971, 2972, 2973, 2974, 2975, 2976, 2977, 2978, 2979, 2980, 2981, 2982, 2983, 2984, 2985, 2986, 2987, 2988, 2989, 2990, 2991, 2992, 2993, 2994, 2995, 2996, 2997, 2998, 2999, 3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021, 3022, 3023, 3024, 3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032, 3033, 3034, 3035, 3036, 3037, 3038, 3039, 3040, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051, 3052, 3053, 3054, 3055, 3056, 3057, 3058, 3059, 3060, 3061, 3062, 3063, 3064, 3065, 3066, 3067, 3068, 3069, 3070, 3071, 3072, 3073, 3074, 3075, 3076, 3077, 3078, 3079, 3080, 3081, 3082, 3083, 3084, 3085, 3086, 3087, 3088, 3089, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099, 3100, 3101, 3102, 3103, 3104, 3105, 3106, 3107, 3108, 3109, 3110, 3111, 3112, 3113, 3114, 3115, 3116, 3117, 3118, 3119, 3120);
DELETE FROM users WHERE id IN (778, 779, 780, 781, 782, 783, 784, 785, 786, 787, 788, 789, 790, 791, 792, 793, 794, 795, 796, 797, 798, 799, 800, 801, 802, 803);

COMMIT;
