import SwiftUI

private enum Palette {
    static let green = Color(red: 0.08, green: 0.42, blue: 0.33)
    static let ink = Color(red: 0.13, green: 0.24, blue: 0.20)
    static let muted = Color(red: 0.39, green: 0.45, blue: 0.42)
    static let background = Color(red: 0.96, green: 0.97, blue: 0.95)
}

struct ContentView: View {
    @EnvironmentObject private var model: RoomsModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingPrivacy = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("KEXIN  /  STUDY SPACE")
                    .font(.system(size: 10, weight: .semibold, design: .rounded))
                    .tracking(2)
                    .foregroundStyle(Palette.green)
                Text("找一间空教室")
                    .font(.system(size: 27, weight: .bold))
                    .foregroundStyle(Palette.ink)
                Text("科信校区 · 河北工程大学")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.muted)
                Text(model.status)
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.muted)
                    .accessibilityAddTraits(.updatesFrequently)
                if model.isBusy { ProgressView().tint(Palette.green).frame(maxWidth: .infinity) }

                if model.isLoggedIn { queryView } else { loginView }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Palette.background)
        .tint(Palette.green)
        .task { model.start() }
        .onChange(of: scenePhase) { phase in
            if phase == .active, model.fetchedAt != nil { model.foreground() }
        }
        .sheet(isPresented: $model.needsCaptcha) { captchaSheet }
        .alert("账号与隐私", isPresented: $showingPrivacy) {
            Button("退出并清除账号", role: .destructive) { model.logout() }
            Button("关闭", role: .cancel) {}
        } message: {
            Text("账号密码保存在本机 Keychain。仅查询空教室，不含选课、预约或成绩功能。退出会清除本机账号与会话。\n\n非学校官方应用 · 1.0.0")
        }
    }

    private var loginView: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("连接你的教务账号")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Palette.ink)
            TextField("学号 / 教务用户名", text: $model.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.username)
                .textFieldStyle(.roundedBorder)
            SecureField("教务密码", text: $model.password)
                .textContentType(.password)
                .textFieldStyle(.roundedBorder)
            Button(action: model.login) {
                Text("登录并查询").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.green)
            .disabled(model.isBusy)
            Text("账号密码仅保存在本机；直接连接学校教务系统。")
                .font(.system(size: 12))
                .foregroundStyle(Palette.muted)
        }
        .cardStyle()
        .padding(.top, 8)
    }

    private var queryView: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let config = model.config, model.filtersVisible {
                VStack(alignment: .leading, spacing: 8) {
                    Text("选择自习时段")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Palette.ink)
                    HStack(spacing: 8) {
                        DatePicker("日期", selection: $model.date, displayedComponents: .date)
                            .labelsHidden()
                            .onChange(of: model.date) { _ in
                                model.followToday = false
                                model.changeFilters()
                            }
                        Button("今天") {
                            model.followToday = true
                            model.date = SchoolProtocol.calendar.startOfDay(for: Date())
                            model.changeFilters()
                        }
                        .font(.system(size: 13))
                        Spacer(minLength: 0)
                    }
                    Text(config.label)
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.muted)
                    HStack(spacing: 10) {
                        compactPicker("开始节次", selection: $model.from, options: config.periods.map { ($0.number, $0.label) })
                        compactPicker("结束节次", selection: $model.to, options: config.periods.map { ($0.number, $0.label) })
                    }
                    HStack(spacing: 10) {
                        compactPicker("教学楼", selection: $model.building, options: config.buildings.map { ($0.id, $0.label) })
                        compactPicker("场地类别", selection: $model.type, options: config.types.map { ($0.id, $0.label) })
                    }
                }
                .cardStyle()
                Button { Task { await model.search() } } label: {
                    Text("查询空教室").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Palette.green)
                .disabled(model.isBusy)
            }

            if !model.summary.isEmpty {
                Text(model.summary)
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !model.filtersVisible {
                Button("修改查询条件") { model.filtersVisible = true }
                    .font(.system(size: 13))
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.bordered)
            }
            if model.rooms.isEmpty, model.fetchedAt != nil {
                VStack(alignment: .leading, spacing: 4) {
                    Text("这个时段没有符合条件的空教室").font(.system(size: 15, weight: .semibold))
                    Text("试试其他节次、教学楼或场地类别。")
                        .font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                .cardStyle()
            }
            LazyVStack(spacing: 5) {
                ForEach(model.rooms) { room in
                    HStack(alignment: .center, spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(room.name).font(.system(size: 17, weight: .bold)).lineLimit(1)
                            Text("\(room.building) · \(room.type)")
                                .font(.system(size: 11))
                                .foregroundStyle(Palette.muted)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 4)
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("\(room.seats) 座")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(Palette.green)
                            Text("可借用 \(room.bookable)")
                                .font(.system(size: 10))
                                .foregroundStyle(Palette.muted)
                        }
                    }
                    .foregroundStyle(Palette.ink)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(.white, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            Text("结果来自学校排课及占用记录，现场开放情况以学校管理为准。")
                .font(.system(size: 10))
                .foregroundStyle(Palette.muted)
                .padding(.top, 4)
            Button("账号与隐私") { showingPrivacy = true }
                .font(.system(size: 12))
                .frame(maxWidth: .infinity)
        }
        .padding(.top, 8)
    }

    private func compactPicker<Value: Hashable>(_ title: String, selection: Binding<Value>, options: [(Value, String)]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 10)).foregroundStyle(Palette.muted)
            Picker(title, selection: selection) {
                ForEach(options.indices, id: \.self) { index in
                    Text(options[index].1).tag(options[index].0)
                }
            }
            .pickerStyle(.menu)
            .font(.system(size: 12))
            .lineLimit(1)
            .onChange(of: selection.wrappedValue) { _ in model.changeFilters() }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var captchaSheet: some View {
        VStack(spacing: 14) {
            Text("学校要求验证码").font(.headline)
            if let image = model.captchaImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(height: 90)
                    .accessibilityLabel("学校登录验证码")
            }
            TextField("输入图片验证码", text: $model.captchaText)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
            Button("继续登录") { model.submitCaptcha() }
                .buttonStyle(.borderedProminent)
            Button("换一张") { Task { await model.refreshCaptcha() } }
            Button("取消") { model.needsCaptcha = false }
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}

private extension View {
    func cardStyle() -> some View {
        self.padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.white, in: RoundedRectangle(cornerRadius: 14))
    }
}
