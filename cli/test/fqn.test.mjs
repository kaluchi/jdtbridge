import { describe, it, expect } from "vitest";
import { parseFqn } from "../src/args.mjs";

describe("parseFqn", () => {
  // ---- Plain FQN (no method) ----

  describe("plain FQN — no method", () => {
    it("simple class name", () => {
      expect(parseFqn("com.example.Foo")).toEqual({
        className: "com.example.Foo",
        method: null,
        paramTypes: null,
      });
    });

    it("single-segment name", () => {
      expect(parseFqn("Foo")).toEqual({
        className: "Foo",
        method: null,
        paramTypes: null,
      });
    });

    it("deep package", () => {
      expect(parseFqn("io.github.kaluchi.jdtbridge.SearchHandler")).toEqual({
        className: "io.github.kaluchi.jdtbridge.SearchHandler",
        method: null,
        paramTypes: null,
      });
    });

    it("null input", () => {
      expect(parseFqn(null)).toEqual({
        className: null,
        method: null,
        paramTypes: null,
      });
    });

    it("undefined input", () => {
      expect(parseFqn(undefined)).toEqual({
        className: null,
        method: null,
        paramTypes: null,
      });
    });

    it("empty string", () => {
      expect(parseFqn("")).toEqual({
        className: null,
        method: null,
        paramTypes: null,
      });
    });
  });

  // ---- Javadoc style: Class#method ----

  describe("javadoc style — hash separator", () => {
    it("method without signature", () => {
      expect(parseFqn("com.example.Foo#bar")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: null,
      });
    });

    it("method with empty parens — zero-arg", () => {
      expect(parseFqn("com.example.Foo#bar()")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: [],
      });
    });

    it("method with one param — simple name", () => {
      expect(parseFqn("com.example.Foo#bar(String)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String"],
      });
    });

    it("method with one param — FQN", () => {
      expect(parseFqn("com.example.Foo#bar(java.lang.String)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["java.lang.String"],
      });
    });

    it("method with multiple params", () => {
      expect(parseFqn("com.example.Foo#bar(String, int)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String", "int"],
      });
    });

    it("method with array param", () => {
      expect(parseFqn("com.example.Foo#bar(String[])")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String[]"],
      });
    });

    it("method with multiple params including array", () => {
      expect(
        parseFqn("com.example.Foo#bar(java.lang.String, java.lang.String[])"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["java.lang.String", "java.lang.String[]"],
      });
    });

    it("method with 2D array param", () => {
      expect(parseFqn("com.example.Foo#bar(int[][])")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["int[][]"],
      });
    });

    it("method with many params", () => {
      expect(
        parseFqn("com.example.Foo#bar(int, String, boolean, double)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["int", "String", "boolean", "double"],
      });
    });

    it("constructor-like (class name as method)", () => {
      expect(parseFqn("com.example.Foo#Foo(int)")).toEqual({
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
        parseFqn(
          "io.github.kaluchi.jdtbridge.SearchHandler.normalizePackage(String)",
        ),
      ).toEqual({
        className: "io.github.kaluchi.jdtbridge.SearchHandler",
        method: "normalizePackage",
        paramTypes: ["String"],
      });
    });

    it("method with empty parens", () => {
      expect(parseFqn("com.example.Foo.bar()")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: [],
      });
    });

    it("method with multiple params", () => {
      expect(parseFqn("com.example.Foo.bar(String, int)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["String", "int"],
      });
    });

    it("method with FQN params", () => {
      expect(
        parseFqn("com.example.Foo.bar(java.lang.String, java.util.List)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["java.lang.String", "java.util.List"],
      });
    });

    it("inner class method", () => {
      expect(parseFqn("com.example.Outer.Inner.method(String)")).toEqual({
        className: "com.example.Outer.Inner",
        method: "method",
        paramTypes: ["String"],
      });
    });

    it("does NOT parse as method when no parens", () => {
      // Without parens, dot notation is ambiguous — treated as plain FQN
      expect(parseFqn("com.example.Foo.bar")).toEqual({
        className: "com.example.Foo.bar",
        method: null,
        paramTypes: null,
      });
    });
  });

  // ---- Generics erasure ----
  // Generics are stripped from param types (type erasure).
  // Map<String,Integer> → Map. This ensures FQNs are canonical
  // and match regardless of generic info.

  describe("generics erasure in parameters", () => {
    it("simple generic param — erased", () => {
      expect(parseFqn("com.example.Foo#bar(List<String>)")).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["List"],
      });
    });

    it("generic with comma inside — single erased param", () => {
      expect(
        parseFqn("com.example.Foo#bar(Map<String, Integer>)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["Map"],
      });
    });

    it("generic param followed by another param", () => {
      expect(
        parseFqn("com.example.Foo#bar(Map<String, Integer>, int)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["Map", "int"],
      });
    });

    it("nested generics — fully erased", () => {
      expect(
        parseFqn("com.example.Foo#bar(Map<String, List<Integer>>)"),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["Map"],
      });
    });

    it("multiple generic params", () => {
      expect(
        parseFqn(
          "com.example.Foo#bar(List<String>, Map<String, Integer>, int)",
        ),
      ).toEqual({
        className: "com.example.Foo",
        method: "bar",
        paramTypes: ["List", "Map", "int"],
      });
    });

    it("generic array — erased but array preserved", () => {
      expect(parseFqn("Foo#bar(List<String>[])")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["List[]"],
      });
    });

    it("FQN generic — erased", () => {
      expect(
        parseFqn("Foo#bar(java.util.Map<java.lang.String, java.lang.Integer>)"),
      ).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["java.util.Map"],
      });
    });

    it("Eclipse Copy Qualified Name with generics", () => {
      expect(
        parseFqn(
          "io.github.kaluchi.jdtbridge.SearchHandler.handleFind(Map<String, String>)",
        ),
      ).toEqual({
        className: "io.github.kaluchi.jdtbridge.SearchHandler",
        method: "handleFind",
        paramTypes: ["Map"],
      });
    });

    it("no generics — unchanged", () => {
      expect(parseFqn("Foo#bar(String, int)")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String", "int"],
      });
    });
  });

  // ---- Inner classes ----

  describe("inner classes", () => {
    it("inner class with hash — method", () => {
      expect(parseFqn("com.example.Outer.Inner#method(String)")).toEqual({
        className: "com.example.Outer.Inner",
        method: "method",
        paramTypes: ["String"],
      });
    });

    it("inner class with hash — no params", () => {
      expect(parseFqn("com.example.Outer.Inner#method")).toEqual({
        className: "com.example.Outer.Inner",
        method: "method",
        paramTypes: null,
      });
    });

    it("deeply nested inner class", () => {
      expect(
        parseFqn("com.example.A.B.C.D#method(int)"),
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
      expect(parseFqn("com.example.Foo#")).toEqual({
        className: "com.example.Foo",
        method: null,
        paramTypes: null,
      });
    });

    it("whitespace in params is trimmed", () => {
      expect(parseFqn("Foo#bar(  String  ,  int  )")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String", "int"],
      });
    });

    it("single param with spaces", () => {
      expect(parseFqn("Foo#bar( String )")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String"],
      });
    });

    it("array params with spaces", () => {
      expect(parseFqn("Foo#bar( String[] , int[] )")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["String[]", "int[]"],
      });
    });

    it("only parens with dot — no package", () => {
      expect(parseFqn("Foo.bar()")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: [],
      });
    });

    it("method reference pasted from stack trace won't match (no parens, dot)", () => {
      // com.example.Foo.bar — treated as FQN since no parens or hash
      expect(parseFqn("com.example.Foo.bar")).toEqual({
        className: "com.example.Foo.bar",
        method: null,
        paramTypes: null,
      });
    });

    it("primitive array types", () => {
      expect(parseFqn("Foo#bar(byte[], char[], long[])")).toEqual({
        className: "Foo",
        method: "bar",
        paramTypes: ["byte[]", "char[]", "long[]"],
      });
    });

    it("no closing paren — still parses", () => {
      expect(parseFqn("Foo#bar(String")).toEqual({
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
        parseFqn(
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
        parseFqn("java.lang.String#valueOf(int)"),
      ).toEqual({
        className: "java.lang.String",
        method: "valueOf",
        paramTypes: ["int"],
      });
    });

    it("Maven Surefire style — no params", () => {
      expect(
        parseFqn("com.example.util.ObjectMapperTest#testSerialize"),
      ).toEqual({
        className: "com.example.util.ObjectMapperTest",
        method: "testSerialize",
        paramTypes: null,
      });
    });

    it("overloaded save with specific signature", () => {
      expect(
        parseFqn("com.example.dao.OrderRepository#save(Order)"),
      ).toEqual({
        className: "com.example.dao.OrderRepository",
        method: "save",
        paramTypes: ["Order"],
      });
    });

    it("JUnit test from user's example", () => {
      expect(
        parseFqn("org.junit.Foo#bar(java.lang.String, java.lang.String[])"),
      ).toEqual({
        className: "org.junit.Foo",
        method: "bar",
        paramTypes: ["java.lang.String", "java.lang.String[]"],
      });
    });

    it("method with no params — explicit zero-arg", () => {
      expect(
        parseFqn("com.example.Service#shutdown()"),
      ).toEqual({
        className: "com.example.Service",
        method: "shutdown",
        paramTypes: [],
      });
    });
  });
});
