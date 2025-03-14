/*
 * Copyright 2006-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.retry.backoff;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.retry.RetryContext;
import org.springframework.util.ClassUtils;

/**
 * Implementation of {@link BackOffPolicy} that increases the back off period for each
 * retry attempt in a given set up to a limit.
 *
 * This implementation is thread-safe and suitable for concurrent access. Modifications to
 * the configuration do not affect any retry sets that are already in progress.
 *
 * The {@link #setInitialInterval(long)} property controls the initial delay value for the
 * first retry and the {@link #setMultiplier(double)} property controls by how much the
 * delay is increased for each subsequent attempt. The delay interval is capped at
 * {@link #setMaxInterval(long)}.
 *
 * @author Rob Harrop
 * @author Dave Syer
 * @author Gary Russell
 * @author Artem Bilan
 */
@SuppressWarnings("serial")
public class ExponentialBackOffPolicy implements SleepingBackOffPolicy<ExponentialBackOffPolicy> {

	protected final Log logger = LogFactory.getLog(this.getClass());

	/**
	 * The default 'initialInterval' value - 100 millisecs. Coupled with the default
	 * 'multiplier' value this gives a useful initial spread of pauses for 1-5 retries.
	 */
	public static final long DEFAULT_INITIAL_INTERVAL = 100L;

	/**
	 * The default maximum backoff time (30 seconds).
	 */
	public static final long DEFAULT_MAX_INTERVAL = 30000L;

	/**
	 * The default 'multiplier' value - value 2 (100% increase per backoff).
	 */
	public static final double DEFAULT_MULTIPLIER = 2;

	/**
	 * The initial backoff interval.
	 */
	private volatile long initialInterval = DEFAULT_INITIAL_INTERVAL;

	/**
	 * The maximum value of the backoff period in milliseconds.
	 */
	private volatile long maxInterval = DEFAULT_MAX_INTERVAL;

	/**
	 * The value to add to the backoff period for each retry attempt.
	 */
	private volatile double multiplier = DEFAULT_MULTIPLIER;

	private Sleeper sleeper = new ThreadWaitSleeper();

	/**
	 * Public setter for the {@link Sleeper} strategy.
	 * @param sleeper the sleeper to set defaults to {@link ThreadWaitSleeper}.
	 */
	public void setSleeper(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	@Override
	public ExponentialBackOffPolicy withSleeper(Sleeper sleeper) {
		ExponentialBackOffPolicy res = newInstance();
		cloneValues(res);
		res.setSleeper(sleeper);
		return res;
	}

	protected ExponentialBackOffPolicy newInstance() {
		return new ExponentialBackOffPolicy();
	}

	protected void cloneValues(ExponentialBackOffPolicy target) {
		target.setInitialInterval(getInitialInterval());
		target.setMaxInterval(getMaxInterval());
		target.setMultiplier(getMultiplier());
		target.setSleeper(this.sleeper);
	}

	/**
	 * Set the initial sleep interval value. Default is {@code 100} millisecond. Cannot be
	 * set to a value less than one.
	 * @param initialInterval the initial interval
	 */
	public void setInitialInterval(long initialInterval) {
		this.initialInterval = (initialInterval > 1 ? initialInterval : 1);
	}

	/**
	 * Set the multiplier value. Default is '<code>2.0</code>'. Hint: do not use values
	 * much in excess of 1.0 (or the backoff will get very long very fast).
	 * @param multiplier the multiplier
	 */
	public void setMultiplier(double multiplier) {
		this.multiplier = (multiplier > 1.0 ? multiplier : 1.0);
	}

	/**
	 * Setter for maximum back off period. Default is 30000 (30 seconds). the value will
	 * be reset to 1 if this method is called with a value less than 1. Set this to avoid
	 * infinite waits if backing off a large number of times (or if the multiplier is set
	 * too high).
	 * @param maxInterval in milliseconds.
	 */
	public void setMaxInterval(long maxInterval) {
		this.maxInterval = maxInterval > 0 ? maxInterval : 1;
	}

	/**
	 * The initial period to sleep on the first backoff.
	 * @return the initial interval
	 */
	public long getInitialInterval() {
		return this.initialInterval;
	}

	/**
	 * The maximum interval to sleep for. Defaults to 30 seconds.
	 * @return the maximum interval.
	 */
	public long getMaxInterval() {
		return this.maxInterval;
	}

	/**
	 * The multiplier to use to generate the next backoff interval from the last.
	 * @return the multiplier in use
	 */
	public double getMultiplier() {
		return this.multiplier;
	}

	/**
	 * Returns a new instance of {@link BackOffContext} with the configured properties.
	 */
	@Override
	public BackOffContext start(RetryContext context) {
		return new ExponentialBackOffContext(this.initialInterval, this.multiplier, this.maxInterval);
	}

	/**
	 * Pause for the current backoff interval.
	 */
	@Override
	public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
		ExponentialBackOffContext context = (ExponentialBackOffContext) backOffContext;
		try {
			long sleepTime = context.getSleepAndIncrement();
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Sleeping for " + sleepTime);
			}
			this.sleeper.sleep(sleepTime);
		}
		catch (InterruptedException e) {
			throw new BackOffInterruptedException("Thread interrupted while sleeping", e);
		}
	}

	static class ExponentialBackOffContext implements BackOffContext {

		private final double multiplier;

		private long interval;

		private final long maxInterval;

		public ExponentialBackOffContext(long interval, double multiplier, long maxInterval) {
			this.interval = interval;
			this.multiplier = multiplier;
			this.maxInterval = maxInterval;
		}

		public synchronized long getSleepAndIncrement() {
			long sleep = this.interval;
			if (sleep > this.maxInterval) {
				sleep = this.maxInterval;
			}
			else {
				this.interval = getNextInterval();
			}
			return sleep;
		}

		protected long getNextInterval() {
			return (long) (this.interval * this.multiplier);
		}

		public double getMultiplier() {
			return this.multiplier;
		}

		public long getInterval() {
			return this.interval;
		}

		public long getMaxInterval() {
			return this.maxInterval;
		}

	}

	@Override
	public String toString() {
		return ClassUtils.getShortName(getClass()) + "[initialInterval=" + this.initialInterval + ", multiplier="
				+ this.multiplier + ", maxInterval=" + this.maxInterval + "]";
	}

}
