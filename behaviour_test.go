package behaviour

import (
	"archive/tar"
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	git "github.com/abxengg/codist-git"
)

// repo builds a git repository from a list of commits, each a map of files.
type repo struct {
	t   *testing.T
	dir string
}

func newRepo(t *testing.T) *repo {
	r := &repo{t: t, dir: t.TempDir()}
	r.git("init", "-q", "-b", "main")
	return r
}

func (r *repo) git(args ...string) string {
	r.t.Helper()
	out, err := exec.Command("git", append([]string{"-C", r.dir, "-c", "user.name=Dev", "-c", "user.email=dev@x", "-c", "commit.gpgsign=false"}, args...)...).CombinedOutput()
	if err != nil {
		r.t.Fatalf("git %v: %s", args, out)
	}
	return strings.TrimSpace(string(out))
}

func (r *repo) commit(msg string, files map[string]string) string {
	r.t.Helper()
	for p, s := range files {
		full := filepath.Join(r.dir, filepath.FromSlash(p))
		os.MkdirAll(filepath.Dir(full), 0o755)
		os.WriteFile(full, []byte(s), 0o644)
	}
	r.git("add", "-A")
	r.git("commit", "-q", "-m", msg)
	return r.git("rev-parse", "HEAD")
}

func (r *repo) runner(t *testing.T) *Runner {
	g, err := git.Open(r.dir)
	if err != nil {
		t.Fatal(err)
	}
	return &Runner{Repo: g, Work: t.TempDir()}
}

func needJDK(t *testing.T) {
	if _, _, err := jdk(); err != nil {
		t.Skip("no JDK on this machine")
	}
}

const calc = "app/src/main/java/shop/Price.java"

const priceV1 = `package shop;

public class Price {
    public static long withTax(long cents, int percent) {
        long tax = cents * percent / 100;
        return cents + tax;
    }

    public static String label(long cents) {
        return "$" + (cents / 100) + "." + String.format("%02d", cents % 100);
    }
}
`

func TestDifferenceFound(t *testing.T) {
	needJDK(t)
	r := newRepo(t)
	r.commit("Add prices", map[string]string{calc: priceV1})
	sha := r.commit("Round tax", map[string]string{calc: strings.Replace(priceV1, "return cents + tax;", "return cents + tax + 1;", 1)})
	rep := r.runner(t).Run(context.Background(), sha)
	if rep.Status != "done" || rep.Differ == 0 || len(rep.Differences) == 0 {
		t.Fatalf("%+v", rep)
	}
	if rep.Module != "app" || strings.Join(rep.Methods, ",") != "shop.Price#withTax" {
		t.Errorf("module %q methods %v", rep.Module, rep.Methods)
	}
	d := rep.Differences[0]
	if d.Method != "shop.Price#withTax" || !strings.HasPrefix(d.Call, "withTax(") || d.Old == d.New {
		t.Errorf("difference %+v", d)
	}
	if rep.Took <= 0 {
		t.Error("took")
	}
}

func TestRefactorShowsNothing(t *testing.T) {
	needJDK(t)
	r := newRepo(t)
	r.commit("Add prices", map[string]string{calc: priceV1})
	sha := r.commit("Rename a local", map[string]string{calc: strings.ReplaceAll(priceV1, "tax", "vat")})
	rep := r.runner(t).Run(context.Background(), sha)
	if rep.Status != "done" || rep.Differ != 0 || rep.Ran == 0 {
		t.Fatalf("%+v", rep)
	}
}

func TestSkips(t *testing.T) {
	needJDK(t)
	r := newRepo(t)
	first := r.commit("Add prices", map[string]string{calc: priceV1})
	docs := r.commit("Docs", map[string]string{"README.md": "# shop\n"})
	broken := r.commit("Break the build", map[string]string{calc: "package shop; class Price { void x() { nope(); } }"})
	r.commit("Use Gradle", map[string]string{"app/build.gradle": "plugins { id 'java' }\n", calc: priceV1})
	gradle := r.commit("Round tax", map[string]string{calc: strings.Replace(priceV1, "return cents + tax;", "return cents + tax + 1;", 1)})
	run := r.runner(t)
	for _, c := range []struct{ sha, status, reason string }{
		{first, "skipped", "first commit"},
		{docs, "skipped", "changes no method"},
		{gradle, "skipped", "Gradle"},
		{broken, "skipped", "does not build here: app/src/main/java/shop/Price.java:1"},
		{"nope", "skipped", "not in the repository"},
	} {
		rep := run.Run(context.Background(), c.sha)
		if rep.Status != c.status || !strings.Contains(rep.Reason, c.reason) {
			t.Errorf("%s: %s %q", c.reason, rep.Status, rep.Reason)
		}
	}
}

func TestMavenProject(t *testing.T) {
	needJDK(t)
	if _, err := exec.LookPath("mvn"); err != nil {
		t.Skip("no Maven on this machine")
	}
	r := newRepo(t)
	pom := `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>shop</groupId><artifactId>app</artifactId><version>1</version></project>`
	r.commit("Add prices", map[string]string{"app/pom.xml": pom, calc: priceV1})
	sha := r.commit("Round tax", map[string]string{calc: strings.Replace(priceV1, "return cents + tax;", "return cents + tax + 1;", 1)})
	run := r.runner(t)
	rep := run.Run(context.Background(), sha)
	if rep.Status != "done" || rep.Module != "app" || rep.Differ == 0 {
		t.Fatalf("%+v", rep)
	}
	again := run.Run(context.Background(), sha) // the classpath comes from the cache the second time
	if again.Differ != rep.Differ {
		t.Errorf("second run %+v", again)
	}
}

func TestCallersAreRun(t *testing.T) {
	needJDK(t)
	r := newRepo(t)
	caller := "app/src/main/java/shop/Cart.java"
	cart := "package shop;\n\npublic class Cart {\n    public long total(long cents) {\n        return Price.withTax(cents, 20);\n    }\n}\n"
	r.commit("Add prices", map[string]string{calc: priceV1, caller: cart})
	sha := r.commit("Round tax", map[string]string{calc: strings.Replace(priceV1, "return cents + tax;", "return cents + tax + 1;", 1)})
	_, methods, err := r.runner(t).Changed(sha)
	if err != nil || strings.Join(methods, ",") != "shop.Cart#total,shop.Price#withTax" {
		t.Errorf("%v %v", methods, err)
	}
}

func TestHelpers(t *testing.T) {
	if got := oldLines("@@ -3,2 +3,2 @@\n a\n-b\n+c\n\\ No newline\n"); len(got) != 1 || got[0] != 4 {
		t.Errorf("oldLines %v", got)
	}
	if moduleOf("svc/src/main/java/A.java") != "svc" || moduleOf("a/b/src/main/java/x/A.java") != "a/b" || moduleOf("src/main/java/A.java") != "" {
		t.Error("moduleOf")
	}
	if lastLine("a\nb\n") != "b" || firstError("x\nfoo.java:1: error: bad\n") != "foo.java:1: error: bad" || firstError("plain") != "plain" {
		t.Error("lines")
	}
	if string(lastJSON([]byte("noise\n{\"a\":1}\n"))) != `{"a":1}` || string(lastJSON([]byte("none"))) != "none" {
		t.Error("lastJSON")
	}
	if quoteArgs([]string{`C:\a b.java`}) != "\"C:\\\\a b.java\"\n" {
		t.Errorf("quoteArgs %q", quoteArgs([]string{`C:\a b.java`}))
	}
	if moduleName("") != "the project" || moduleName("a/b") != "b" {
		t.Error("moduleName")
	}
}

func TestUntarRefusesEscapes(t *testing.T) {
	var b bytes.Buffer
	w := tar.NewWriter(&b)
	w.WriteHeader(&tar.Header{Name: "../evil.txt", Mode: 0o644, Size: 1, Typeflag: tar.TypeReg})
	w.Write([]byte("x"))
	w.Close()
	if err := untar(&b, t.TempDir()); err == nil {
		t.Error("a path outside the folder was written")
	}
	if err := copyTree(filepath.Join(t.TempDir(), "missing"), t.TempDir()); err != nil {
		t.Errorf("copying nothing: %v", err)
	}
}

func TestCleanEnv(t *testing.T) {
	t.Setenv("AWS_SECRET_ACCESS_KEY", "secret")
	t.Setenv("PATH", "/bin")
	env := strings.Join(cleanEnv("/w"), "\n")
	if strings.Contains(env, "secret") || !strings.Contains(env, "PATH=/bin") || !strings.Contains(env, "HOME=/w") {
		t.Errorf("%s", env)
	}
}

// A service that uses a sibling module builds through the parent, as the Orbit Commerce services do.
func TestMavenModules(t *testing.T) {
	needJDK(t)
	if _, err := exec.LookPath("mvn"); err != nil {
		t.Skip("no Maven on this machine")
	}
	r := newRepo(t)
	parent := `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>shop</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
<properties><maven.compiler.release>17</maven.compiler.release></properties>
<modules><module>lib</module><module>app</module></modules></project>`
	child := func(name, deps string) string {
		return `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<parent><groupId>shop</groupId><artifactId>parent</artifactId><version>1</version></parent>
<artifactId>` + name + `</artifactId><dependencies>` + deps + `</dependencies></project>`
	}
	rates := "package shop.lib;\n\npublic class Rates {\n    public static int vat() { return 20; }\n}\n"
	cart := func(expr string) string {
		return "package shop;\n\nimport shop.lib.Rates;\n\npublic class Cart {\n    public static long total(long cents) {\n        return " + expr + ";\n    }\n}\n"
	}
	r.commit("Add shop", map[string]string{"pom.xml": parent, "lib/pom.xml": child("lib", ""),
		"app/pom.xml":                           child("app", `<dependency><groupId>shop</groupId><artifactId>lib</artifactId><version>1</version></dependency>`),
		"lib/src/main/java/shop/lib/Rates.java": rates, "app/src/main/java/shop/Cart.java": cart("cents + cents * Rates.vat() / 100")})
	sha := r.commit("Round up", map[string]string{"app/src/main/java/shop/Cart.java": cart("cents + cents * Rates.vat() / 100 + 1")})
	rep := r.runner(t).Run(context.Background(), sha)
	if rep.Status != "done" || rep.Module != "app" || rep.Differ == 0 {
		t.Fatalf("%+v", rep)
	}
}

func TestMavenError(t *testing.T) {
	out := "[INFO] x\n[ERROR] Failed to execute goal on project app: Could not resolve dependencies\n[ERROR] \n[ERROR] -> [Help 1]\n[ERROR] Re-run Maven\n"
	if got := mavenError(out); got != "Failed to execute goal on project app: Could not resolve dependencies" {
		t.Errorf("%q", got)
	}
	if mavenError("[ERROR] -> [Help 1]\nboom") != "boom" {
		t.Error("no real error line")
	}
}

func TestBuildEnv(t *testing.T) {
	t.Setenv("AWS_SECRET_ACCESS_KEY", "secret")
	t.Setenv("MAVEN_OPTS", "-Xmx1g")
	t.Setenv("HOME", "/home/dev")
	env := strings.Join(buildEnv(), "\n")
	if strings.Contains(env, "secret") || !strings.Contains(env, "MAVEN_OPTS=-Xmx1g") || !strings.Contains(env, "HOME=/home/dev") {
		t.Errorf("%s", env)
	}
}

func TestNothingRan(t *testing.T) {
	needJDK(t)
	r := newRepo(t)
	r.commit("Add prices", map[string]string{calc: priceV1})
	sha := r.commit("Add a cart", map[string]string{"app/src/main/java/shop/Cart.java": "package shop;\n\npublic class Cart {\n    public long one() { return 1; }\n}\n"})
	rep := r.runner(t).Run(context.Background(), sha)
	if rep.Status != "skipped" || !strings.Contains(rep.Reason, "adds this code") {
		t.Errorf("%+v", rep)
	}
	if !strings.Contains(nothingRan([]string{"page: not in the old code", "no class a.B"}), "adds this code") ||
		!strings.Contains(nothingRan([]string{"x: no way to make a Y"}), "no way to make") || !strings.Contains(nothingRan(nil), "no inputs") {
		t.Error("reasons")
	}
}
