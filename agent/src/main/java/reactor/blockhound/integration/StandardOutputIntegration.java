/*
 * Copyright (c) 2020-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.blockhound.integration;

import com.google.auto.service.AutoService;
import reactor.blockhound.BlockHound;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

@AutoService(BlockHoundIntegration.class)
public class StandardOutputIntegration implements BlockHoundIntegration {

	@Override
	public void applyTo(BlockHound.Builder builder) {
		System.setOut(new PrintStreamDelegate(System.out));
		System.setErr(new PrintStreamDelegate(System.err));

		for (String method : new String[]{
				"flush",
				"close",
				"checkError",
				"write",
				"print",
				"println",
				"printf",
				"format",
				"append",
				"write"
		}) {
			builder.allowBlockingCallsInside(PrintStreamDelegate.class.getName(), method);
		}
	}

	static class PrintStreamDelegate extends PrintStream {

		final PrintStream delegate;

		PrintStreamDelegate(PrintStream delegate) {
			super(delegate);
			this.delegate = delegate;
		}

		@Override
		public void flush() {
			delegate.flush();
		}

		@Override
		public void close() {
			delegate.close();
		}

		@Override
		public boolean checkError() {
			return delegate.checkError();
		}

		@Override
		public void write(int b) {
			delegate.write(b);
		}

		@Override
		public void write(byte[] buf, int off, int len) {
			delegate.write(buf, off, len);
		}

		@Override
		public void print(boolean b) {
			delegate.print(b);
		}

		@Override
		public void print(char c) {
			delegate.print(c);
		}

		@Override
		public void print(int i) {
			delegate.print(i);
		}

		@Override
		public void print(long l) {
			delegate.print(l);
		}

		@Override
		public void print(float f) {
			delegate.print(f);
		}

		@Override
		public void print(double d) {
			delegate.print(d);
		}

		@Override
		public void print(char[] s) {
			delegate.print(s);
		}

		@Override
		public void print(String s) {
			delegate.print(s);
		}

		@Override
		public void print(Object obj) {
			delegate.print(obj);
		}

		@Override
		public void println() {
			delegate.println();
		}

		@Override
		public void println(boolean x) {
			delegate.println(x);
		}

		@Override
		public void println(char x) {
			delegate.println(x);
		}

		@Override
		public void println(int x) {
			delegate.println(x);
		}

		@Override
		public void println(long x) {
			delegate.println(x);
		}

		@Override
		public void println(float x) {
			delegate.println(x);
		}

		@Override
		public void println(double x) {
			delegate.println(x);
		}

		@Override
		public void println(char[] x) {
			delegate.println(x);
		}

		@Override
		public void println(String x) {
			delegate.println(x);
		}

		@Override
		public void println(Object x) {
			delegate.println(x);
		}

		@Override
		public PrintStream printf(String format, Object... args) {
			return delegate.printf(format, args);
		}

		@Override
		public PrintStream printf(Locale l, String format, Object... args) {
			return delegate.printf(l, format, args);
		}

		@Override
		public PrintStream format(String format, Object... args) {
			return delegate.format(format, args);
		}

		@Override
		public PrintStream format(Locale l, String format, Object... args) {
			return delegate.format(l, format, args);
		}

		@Override
		public PrintStream append(CharSequence csq) {
			return delegate.append(csq);
		}

		@Override
		public PrintStream append(CharSequence csq, int start, int end) {
			return delegate.append(csq, start, end);
		}

		@Override
		public PrintStream append(char c) {
			return delegate.append(c);
		}

		@Override
		public void write(byte[] b) throws IOException {
			delegate.write(b);
		}

		@Override
		public String toString() {
			return delegate.toString();
		}
	}
}
