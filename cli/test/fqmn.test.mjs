import { describe, it, expect } from "vitest";
import { parseFqmn } from "../src/args.mjs";

describe("parseFqmn", () => {
  // ---- Plain FQN (no method) ----

  describe("plain FQN — no method", () => {
    it("simple class name", () => {
      expect(parseFqmn("com.example.Foo")).toEqual({
        className: "com.example.Foo",
        method: null,
        paramTypes: null,
      });
    });

    it("single-segment name", () => {
      expect(parseFqmn("Foo")).toEqual({
        className: "Foo",
        method: null,
        paramTypes: null,
      });
    });

    it("deep package", () => {
      expect(parseFqmn("io.github.kaluchi.jdtbridge.SearchHandler")).toEqual({
        className: "io.github.kaluchi.jdtbridge.SearchHandler",
        method: null,
        paramTypes: null,
      });
    });

    it("null input", () => {
      expect(parseFqmn(null)).toEqual({
        className: null,
        method: null,
        paramTypes: null,
      });
    });

    it("undefined input", () => {
      expect(parseFqmn(undefined)).toEqual({
        className: null,
        method: null,
        paramTypes: null,
      });
    });

    it("empty string", () => {
      expect(parseFqmn("")).toEqual({
        className: null,
        method: null,
        paramTypes: null,
      });
    });
  });

  // ---- Javadoc style: Class#method ----

  describe("javadoc style — hash separator", () => {
    it("method without signature", () => {
      expect(parseFqmn("com.example.Foo#bar")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: null,
      });
    });

    it("method with empty parens — zero-arg", () => {
      expect(parseFqmn("com.example.Foo#bar()")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: [],
      });
    });

    it("method with one param — simple name", () => {
      expect(parseFqmn("com.example.Foo#bar(String)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String"],
      });
    });

    it("method with one param — FQN", () => {
      expect(parseFqmn("com.example.Foo#bar(java.lang.String)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["java.lang.String"],
      });
    });

    it("method with multiple params", () => {
      expect(parseFqmn("com.example.Foo#bar(String, int)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String", "int"],
      });
    });

    it("method with array param", () => {
      expect(parseFqmn("com.example.Foo#bar(String[])")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String[]"],
      });
    });

    it("method with multiple params including array", () => {
      expect(
        parseFqmn("com.example.Foo#bar(java.lang.String, java.lang.String[])"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["java.lang.String", "java.lang.String[]"],
      });
    });

    it("method with 2D array param", () => {
      expect(parseFqmn("com.example.Foo#bar(int[][])")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["int[][]"],
      });
    });

    it("method with many params", () => {
      expect(
        parseFqmn("com.example.Foo#bar(int, String, boolean, double)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["int", "String", "boolean", "double"],
      });
    });

    it("constructor-like (class name as method)", () => {
      expect(parseFqmn("com.example.Foo#Foo(int)")).toEqual({
        className: "com.example.Foo",
        method: "Foo",
        paramTypes: ["int"],
      });
    });
  });

  // ---- Eclipse Copy Qualified Name style: Class.method(params) ----

  describe("Eclipse copy style — dot separator with parens", () => {
    it("method with one param", () => {
      expect(
        parseFqmn(
          "io.github.kaluchi.jdtbridge.SearchHandler.normalizePackage(String)",
        ),
      ).toEqual({
        className: "io.github.kaluchi.jdtbridge.SearchHandler",
        method: "normalizePackage",
        paramTypes: ["String"],
      });
    });

    it("method with empty parens", () => {
      expect(parseFqmn("com.example.Foo.bar()")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: [],
      });
    });

    it("method with multiple params", () => {
      expect(parseFqmn("com.example.Foo.bar(String, int)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String", "int"],
      });
    });

    it("method with FQN params", () => {
      expect(
        parseFqmn("com.example.Foo.bar(java.lang.String, java.util.List)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["java.lang.String", "java.util.List"],
      });
    });

    it("inner class method", () => {
      expect(parseFqmn("com.example.Outer.Inner.method(String)")).toEqual({
        className: "com.example.Outer.Inner",
        method: "method",
        paramTypes: ["String"],
      });
    });

    it("does NOT parse as method when no parens", () => {
      // Without parens, dot notation is ambiguous — treated as plain FQN
      expect(parseFqmn("com.example.Foo.bar")).toEqual({
        className: "com.example.Foo.bar",
        method: null,
        paramTypes: null,
      });
    });
  });

  // ---- Generics handling ----

  describe("generics in parameters", () => {
    it("simple generic param — angle brackets preserved", () => {
      expect(parseFqmn("com.example.Foo#bar(List<String>)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["List<String>"],
      });
    });

    it("generic with comma inside — not split", () => {
      expect(
        parseFqmn("com.example.Foo#bar(Map<String, Integer>)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["Map<String, Integer>"],
      });
    });

    it("generic param followed by another param", () => {
      expect(
        parseFqmn("com.example.Foo#bar(Map<String, Integer>, int)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["Map<String, Integer>", "int"],
      });
    });

    it("nested generics", () => {
      expect(
        parseFqmn("com.example.Foo#bar(Map<String, List<Integer>>)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["Map<String, List<Integer>>"],
      });
    });

    it("multiple generic params", () => {
      expect(
        parseFqmn(
          "com.example.Foo#bar(List<String>, Map<String, Integer>, int)",
        ),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["List<String>", "Map<String, Integer>", "int"],
      });
    });
  });

  // ---- Inner classes ----

  describe("inner classes", () => {
    it("inner class with hash — method", () => {
      expect(parseFqmn("com.example.Outer.Inner#method(String)")).toEqual({
        className: "com.example.Outer.Inner",
        method: "method",
        paramTypes: ["String"],
      });
    });

    it("inner class with hash — no params", () => {
      expect(parseFqmn("com.example.Outer.Inner#method")).toEqual({
        className: "com.example.Outer.Inner",
        method: "method",
        paramTypes: null,
      });
    });

    it("deeply nested inner class", () => {
      expect(
        parseFqmn("com.example.A.B.C.D#method(int)"),
      ).toEqual({
        className: "com.example.A.B.C.D",
        method: "method",
        paramTypes: ["int"],
      });
    });
  });

  // ---- Edge cases ----

  describe("edge cases", () => {
    it("hash at end — empty method name", () => {
      expect(parseFqmn("com.example.Foo#")).toEqual({
        className: "com.example.Foo",
        method: null,
        paramTypes: null,
      });
    });

    it("whitespace in params is trimmed", () => {
      expect(parseFqmn("Foo#bar(  String  ,  int  )")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String", "int"],
      });
    });

    it("single param with spaces", () => {
      expect(parseFqmn("Foo#bar( String )")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String"],
      });
    });

    it("array params with spaces", () => {
      expect(parseFqmn("Foo#bar( String[] , int[] )")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String[]", "int[]"],
      });
    });

    it("only parens with dot — no package", () => {
      expect(parseFqmn("Foo.bar()")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: [],
      });
    });

    it("method reference pasted from stack trace won't match (no parens, dot)", () => {
      // com.example.Foo.bar — treated as FQN since no parens or hash
      expect(parseFqmn("com.example.Foo.bar")).toEqual({
        className: "com.example.Foo.bar",
        method: null,
        paramTypes: null,
      });
    });

    it("primitive array types", () => {
      expect(parseFqmn("Foo#bar(byte[], char[], long[])")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["byte[]", "char[]", "long[]"],
      });
    });

    it("no closing paren — still parses", () => {
      expect(parseFqmn("Foo#bar(String")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String"],
      });
    });
  });

  // ---- Real-world examples ----

  describe("real-world examples", () => {
    it("Eclipse Copy Qualified Name — long FQN", () => {
      expect(
        parseFqmn(
          "io.github.kaluchi.jdtbridge.SearchHandler.normalizePackage(String)",
        ),
      ).toEqual({
        className: "io.github.kaluchi.jdtbridge.SearchHandler",
        method: "normalizePackage",
        paramTypes: ["String"],
      });
    });

    it("javadoc @see style", () => {
      expect(
        parseFqmn("java.lang.String#valueOf(int)"),
      ).toEqual({
        className: "java.lang.String",
        method: "valueOf",
        paramTypes: ["int"],
      });
    });

    it("Maven Surefire style — no params", () => {
      expect(
        parseFqmn("com.example.util.ObjectMapperTest#testSerialize"),
      ).toEqual({
        className: "com.example.util.ObjectMapperTest",
        method: "testSerialize",
        paramTypes: null,
      });
    });

    it("overloaded save with specific signature", () => {
      expect(
        parseFqmn("com.example.dao.OrderRepository#save(Order)"),
      ).toEqual({
        className: "com.example.dao.OrderRepository",
        method: "save",
        paramTypes: ["Order"],
      });
    });

    it("JUnit test from user's example", () => {
      expect(
        parseFqmn("org.junit.Foo#bar(java.lang.String, java.lang.String[])"),
      ).toEqual({
        className: "org.junit.Foo",
        method: "bar",
        paramTypes: ["java.lang.String", "java.lang.String[]"],
      });
    });

    it("method with no params — explicit zero-arg", () => {
      expect(
        parseFqmn("com.example.Service#shutdown()"),
      ).toEqual({
        className: "com.example.Service",
        method: "shutdown",
        paramTypes: [],
      });
    });
  });
});
