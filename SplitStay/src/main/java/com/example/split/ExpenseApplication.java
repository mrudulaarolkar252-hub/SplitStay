package com.example.split;

import jakarta.persistence.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@SpringBootApplication
public class ExpenseApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExpenseApplication.class, args);
    }
}

// --- ENTITIES ---
@Entity @Table(name = "users")
class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(unique = true) private String email;
    private String password;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}

@Entity @Table(name = "expense_groups")
class Group {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private Long userId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "group_id")
    private List<Member> members = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "group_id")
    private List<Expense> expenses = new ArrayList<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public List<Member> getMembers() { return members; }
    public List<Expense> getExpenses() { return expenses; }
}

@Entity
class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

@Entity
class Expense {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String description;
    private BigDecimal amount;
    private Long paidByMemberId;
    private String date;

    public Long getId() { return id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Long getPaidByMemberId() { return paidByMemberId; }
    public void setPaidByMemberId(Long paidByMemberId) { this.paidByMemberId = paidByMemberId; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
}

// --- REPOSITORIES ---
interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
interface GroupRepository extends JpaRepository<Group, Long> {
    List<Group> findByUserId(Long userId);
}

// --- MVC CONTROLLER ---
@Controller
class WebController {

    private final UserRepository userRepo;
    private final GroupRepository groupRepo;

    public WebController(UserRepository userRepo, GroupRepository groupRepo) {
        this.userRepo = userRepo;
        this.groupRepo = groupRepo;
    }

    @GetMapping("/")
    public String index(HttpSession session, @RequestParam(required = false) Long groupId, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        List<Group> groups = groupRepo.findByUserId(user.getId());
        model.addAttribute("user", user);
        model.addAttribute("groups", groups);

        if (!groups.isEmpty()) {
            Group currentGroup = (groupId != null) 
                ? groupRepo.findById(groupId).orElse(groups.get(0)) 
                : groups.get(0);

            model.addAttribute("currentGroup", currentGroup);

            double total = currentGroup.getExpenses().stream()
                    .mapToDouble(e -> e.getAmount().doubleValue()).sum();
            int count = currentGroup.getMembers().size();
            double fairShare = count > 0 ? total / count : 0;

            model.addAttribute("totalExpenses", total);
            model.addAttribute("fairShare", fairShare);

            List<Map<String, Object>> memberStats = new ArrayList<>();
            for (Member m : currentGroup.getMembers()) {
                double paid = currentGroup.getExpenses().stream()
                        .filter(e -> e.getPaidByMemberId().equals(m.getId()))
                        .mapToDouble(e -> e.getAmount().doubleValue()).sum();
                Map<String, Object> stat = new HashMap<>();
                stat.put("id", m.getId());
                stat.put("name", m.getName());
                stat.put("paid", paid);
                stat.put("balance", paid - fairShare);
                memberStats.add(stat);
            }
            model.addAttribute("memberStats", memberStats);
        }

        return "dashboard";
    }

    @GetMapping("/login")
    public String loginPage() { return "login"; }

    @PostMapping("/login")
    public String handleLogin(@RequestParam String email, @RequestParam String password, HttpSession session, Model model) {
        Optional<User> userOpt = userRepo.findByEmail(email);
        if (userOpt.isPresent() && userOpt.get().getPassword().equals(password)) {
            session.setAttribute("user", userOpt.get());
            return "redirect:/";
        }
        model.addAttribute("error", "Invalid credentials!");
        return "login";
    }

    @PostMapping("/register")
    public String handleRegister(@RequestParam String name, @RequestParam String email, @RequestParam String password, Model model) {
        if (userRepo.findByEmail(email).isPresent()) {
            model.addAttribute("error", "Email already exists!");
            return "login";
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(password);
        userRepo.save(user);
        return "redirect:/login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @PostMapping("/groups/create")
    public String createGroup(@RequestParam String name, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";
        Group g = new Group();
        g.setName(name);
        g.setUserId(user.getId());
        groupRepo.save(g);
        return "redirect:/";
    }

    @PostMapping("/members/add")
    public String addMember(@RequestParam Long groupId, @RequestParam String name) {
        Group g = groupRepo.findById(groupId).orElseThrow();
        Member m = new Member();
        m.setName(name);
        g.getMembers().add(m);
        groupRepo.save(g);
        return "redirect:/?groupId=" + groupId;
    }

    @PostMapping("/expenses/add")
    public String addExpense(@RequestParam Long groupId, @RequestParam String description, 
                             @RequestParam BigDecimal amount, @RequestParam Long paidByMemberId, 
                             @RequestParam String date) {
        Group g = groupRepo.findById(groupId).orElseThrow();
        Expense e = new Expense();
        e.setDescription(description);
        e.setAmount(amount);
        e.setPaidByMemberId(paidByMemberId);
        e.setDate(date);
        g.getExpenses().add(e);
        groupRepo.save(g);
        return "redirect:/?groupId=" + groupId;
    }
}