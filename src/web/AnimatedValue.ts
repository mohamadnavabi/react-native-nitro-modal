/// <reference lib="dom" />

/** How an `AnimatedValue` moves to its target. */
export type Motion =
  | {
      type: 'spring';
      /** Period of the undamped spring, in seconds. Lower is snappier. */
      response: number;
      /** 1 settles without overshoot; lower values bounce. */
      dampingRatio: number;
    }
  | {
      type: 'timing';
      /** In milliseconds. */
      duration: number;
      easing: (progress: number) => number;
    };

/** Jumps to the target at once. The completion still arrives on the next frame. */
export const INSTANT: Motion = {
  type: 'timing',
  duration: 0,
  easing: (t) => t,
};

export interface AnimateOptions {
  /** Starting velocity in units per second. Defaults to the current velocity. */
  velocity?: number;
  /** Finish as soon as a spring reaches the target instead of when it settles. */
  stopAtTarget?: boolean;
  /** Runs once the target is reached, unless another animation or `set` takes over first. */
  onEnd?: () => void;
}

interface Run {
  target: number;
  motion: Motion;
  options: AnimateOptions;
  from: number;
  startTime: number;
  /** Side of the target the value started on, for `stopAtTarget`. */
  side: number;
}

/** Longest frame integrated at once, so a dropped frame can't throw the spring off. */
const MAX_FRAME_SECONDS = 0.064;
const SPRING_STEP_SECONDS = 0.004;

/**
 * A number animated on `requestAnimationFrame` that reports every frame
 * through `onChange`. A new animation starts from the current value and
 * velocity, so transitions can be turned around mid-flight.
 */
export class AnimatedValue {
  private current: number;
  private speed = 0;
  private run: Run | null = null;
  private frame: number | null = null;
  private lastTime = 0;

  /** `precision`: distance from the target at which a spring counts as settled. */
  constructor(
    value: number,
    private readonly precision: number,
    private readonly onChange: (value: number) => void
  ) {
    this.current = value;
  }

  get value(): number {
    return this.current;
  }

  /** Jumps to `value`, cancelling any animation. */
  set(value: number) {
    this.stop();
    this.current = value;
    this.speed = 0;
    this.onChange(value);
  }

  stop() {
    this.run = null;
    if (this.frame != null) {
      cancelAnimationFrame(this.frame);
      this.frame = null;
    }
  }

  animateTo(target: number, motion: Motion, options: AnimateOptions = {}) {
    if (options.velocity !== undefined) {
      this.speed = options.velocity;
    }
    const now = performance.now();
    this.run = {
      target,
      motion,
      options,
      from: this.current,
      startTime: now,
      side: Math.sign(this.current - target),
    };
    if (motion.type === 'timing' && motion.duration <= 0) {
      this.current = target;
      this.speed = 0;
      this.onChange(target);
    }
    if (this.frame == null) {
      this.lastTime = now;
      this.frame = requestAnimationFrame(this.step);
    }
  }

  private step = (time: number) => {
    this.frame = null;
    const run = this.run;
    if (!run) return;
    const dt = Math.min(
      Math.max((time - this.lastTime) / 1000, 0),
      MAX_FRAME_SECONDS
    );
    this.lastTime = time;

    const done =
      run.motion.type === 'timing'
        ? this.stepTiming(run, run.motion, time, dt)
        : this.stepSpring(run, run.motion, dt);
    if (done) {
      this.current = run.target;
      this.speed = 0;
      this.run = null;
    }
    this.onChange(this.current);
    if (done) {
      run.options.onEnd?.();
    } else {
      this.frame = requestAnimationFrame(this.step);
    }
  };

  private stepTiming(
    run: Run,
    motion: Extract<Motion, { type: 'timing' }>,
    time: number,
    dt: number
  ): boolean {
    const progress =
      motion.duration > 0
        ? Math.min(Math.max((time - run.startTime) / motion.duration, 0), 1)
        : 1;
    const next = run.from + (run.target - run.from) * motion.easing(progress);
    if (dt > 0) {
      this.speed = (next - this.current) / dt;
    }
    this.current = next;
    return progress >= 1;
  }

  private stepSpring(
    run: Run,
    motion: Extract<Motion, { type: 'spring' }>,
    dt: number
  ): boolean {
    const omega = (2 * Math.PI) / motion.response;
    const stiffness = omega * omega;
    const damping = 2 * motion.dampingRatio * omega;
    let offset = this.current - run.target;
    let velocity = this.speed;
    const steps = Math.ceil(dt / SPRING_STEP_SECONDS);
    for (let i = 0; i < steps; i++) {
      const h = dt / steps;
      velocity += (-stiffness * offset - damping * velocity) * h;
      offset += velocity * h;
    }
    this.current = run.target + offset;
    this.speed = velocity;

    if (
      run.options.stopAtTarget &&
      (run.side === 0 || Math.sign(offset) !== run.side)
    ) {
      return true;
    }
    return (
      Math.abs(offset) < this.precision &&
      Math.abs(velocity) < this.precision * 20
    );
  }
}
