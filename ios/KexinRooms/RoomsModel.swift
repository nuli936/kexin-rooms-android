import Foundation
import UIKit

@MainActor
final class RoomsModel: ObservableObject {
    @Published var username = ""
    @Published var password = ""
    @Published var captchaText = ""
    @Published var captchaImage: UIImage?
    @Published var needsCaptcha = false
    @Published var isBusy = false
    @Published var isLoggedIn = false
    @Published var status = "正在检查本机登录信息…"
    @Published var summary = ""
    @Published var config: SchoolConfig?
    @Published var rooms: [SchoolRoom] = []
    @Published var filtersVisible = true
    @Published var date = Date()
    @Published var followToday = true
    @Published var from = 1
    @Published var to = 2
    @Published var building = ""
    @Published var type = "03"
    @Published var fetchedAt: Date?

    private var client = SchoolClient()
    private var credentials: SavedCredentials?
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        do {
            guard let stored = try CredentialStore.load() else { status = "首次登录后，下次打开自动连接。"; return }
            credentials = stored
            username = stored.username
            Task { await authenticate() }
        } catch {
            status = error.localizedDescription
        }
    }

    func login() {
        let user = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !user.isEmpty, !password.isEmpty else { status = "请输入账号和密码。"; return }
        credentials = SavedCredentials(username: user, password: password)
        password = ""
        Task { await authenticate() }
    }

    func authenticate(captcha: String? = nil) async {
        guard let credentials, !isBusy else { return }
        isBusy = true
        status = "正在登录并读取科信校区配置…"
        do {
            try await client.login(username: credentials.username, password: credentials.password, captcha: captcha)
            let newConfig = try await client.config()
            try CredentialStore.save(credentials)
            config = newConfig
            isLoggedIn = true
            needsCaptcha = false
            isBusy = false
            status = "已连接 · 东校区（科信学院）"
            await search()
        } catch SchoolError.captchaRequired {
            isBusy = false
            await refreshCaptcha()
        } catch {
            isBusy = false
            isLoggedIn = false
            self.credentials = nil
            status = error.localizedDescription
        }
    }

    func refreshCaptcha() async {
        do {
            captchaImage = UIImage(data: try await client.captchaImage())
            captchaText = ""
            needsCaptcha = true
            status = "学校要求验证码，请手动填写。"
        } catch {
            needsCaptcha = false
            status = error.localizedDescription
        }
    }

    func submitCaptcha() {
        let code = captchaText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }
        needsCaptcha = false
        Task { await authenticate(captcha: code) }
    }

    func changeFilters() {
        rooms = []
        fetchedAt = nil
        summary = "条件已更新，请点击查询。"
        filtersVisible = true
    }

    func search() async {
        guard config != nil, !isBusy else { return }
        if followToday { date = SchoolProtocol.calendar.startOfDay(for: Date()) }
        rooms = []
        fetchedAt = nil
        summary = "正在重新核对学校当前学年、学期和校历…"
        isBusy = true
        status = "正在实时查询科信校区…"
        let selectedDate = date
        let selectedFrom = from, selectedTo = to
        let selectedBuilding = building, selectedType = type
        do {
            let result: (SchoolConfig, [SchoolRoom], Date)
            do {
                result = try await client.search(date: selectedDate, from: selectedFrom, to: selectedTo,
                                                 building: selectedBuilding, type: selectedType)
            } catch SchoolError.authExpired {
                guard let credentials else { throw SchoolError.authExpired }
                try await client.login(username: credentials.username, password: credentials.password)
                result = try await client.search(date: selectedDate, from: selectedFrom, to: selectedTo,
                                                 building: selectedBuilding, type: selectedType)
            }
            config = result.0
            rooms = result.1
            fetchedAt = result.2
            filtersVisible = result.1.isEmpty
            let day = DateFormatter()
            day.timeZone = SchoolProtocol.calendar.timeZone
            day.dateFormat = "yyyy-MM-dd"
            let clock = DateFormatter()
            clock.timeZone = SchoolProtocol.calendar.timeZone
            clock.dateFormat = "HH:mm:ss"
            summary = "\(day.string(from: selectedDate)) · 第\(selectedFrom)—\(selectedTo)节\n学校返回 \(rooms.count) 个空闲场地 · \(clock.string(from: result.2)) 更新"
            status = "查询完成 · 科信校区 · 当前学期已核对"
        } catch SchoolError.captchaRequired {
            await refreshCaptcha()
        } catch {
            rooms = []
            summary = "查询失败，未展示旧结果。点击查询可重试。"
            status = error.localizedDescription
        }
        isBusy = false
    }

    func foreground() {
        guard isLoggedIn, !isBusy else { return }
        Task { await search() }
    }

    func logout() {
        CredentialStore.clear()
        client.clear()
        client = SchoolClient()
        credentials = nil
        username = ""
        password = ""
        config = nil
        rooms = []
        summary = ""
        isLoggedIn = false
        status = "已清除本机账号和会话。"
    }
}
